"""MinerU-like 文档解析服务的核心流水线实现。"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import shutil
import subprocess
import threading
import uuid
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any, Iterable

try:
    from .config import ServiceSettings
    from .models import PageArtifact, ParseSummary, RagAssetPayload, RagParseData, to_posix
except ImportError:  # pragma: no cover - compatible with direct script execution
    from config import ServiceSettings
    from models import PageArtifact, ParseSummary, RagAssetPayload, RagParseData, to_posix


OFFICE_SUFFIXES = {
    ".doc",
    ".docx",
    ".ppt",
    ".pptx",
    ".xls",
    ".xlsx",
}
SUPPORTED_SUFFIXES = OFFICE_SUFFIXES | {
    ".pdf",
    ".png",
    ".jpg",
    ".jpeg",
    ".bmp",
    ".webp",
    ".tif",
    ".tiff",
}


class ParseError(RuntimeError):
    """Raised when parsing fails."""


@dataclass(frozen=True)
class PipelineOptions:
    """缓存 PaddleOCR pipeline 实例时使用的配置键。"""

    lang: str
    device: str
    parse_mode: str
    use_chart_recognition: bool
    use_formula_recognition: bool
    use_seal_recognition: bool
    use_table_recognition: bool


class MinerULikeParserService:
    """封装文件标准化、解析执行与结果落盘的服务对象。"""

    def __init__(self, settings: ServiceSettings):
        """使用服务配置初始化缓存、输出目录与临时目录。"""

        self.settings = settings
        self._cache_lock = threading.Lock()
        # 由于 PaddleOCR pipeline 实例是重资源对象，所以这里使用了缓存
        self._pipeline_cache: dict[PipelineOptions, Any] = {}
        self.settings.output_root.mkdir(parents=True, exist_ok=True)
        self.settings.temp_root.mkdir(parents=True, exist_ok=True)

    @property
    def pipeline_cache_size(self) -> int:
        """返回当前已缓存的 pipeline 实例数量。"""

        return len(self._pipeline_cache)

    def parse_file_for_rag(
        self,
        *,
        original_filename: str,
        source_path: Path,
        lang: str | None,
        device: str | None,
        parse_mode: str | None,
        save_artifacts: bool | None,
        include_raw_payload: bool | None,
        use_chart_recognition: bool,
        use_formula_recognition: bool,
        use_seal_recognition: bool,
        use_table_recognition: bool,
    ) -> RagParseData:
        """解析已落盘文件并返回携带内联资源的 Rag 结果。"""

        if not source_path.exists() or not source_path.is_file():
            raise ParseError("待解析文件不存在")

        file_size = source_path.stat().st_size
        if file_size <= 0:
            raise ParseError("上传内容为空")
        if file_size > self.settings.max_upload_mb * 1024 * 1024:
            raise ParseError(f"文件超过限制: {self.settings.max_upload_mb} MB")

        suffix = source_path.suffix.lower()
        if suffix not in SUPPORTED_SUFFIXES:
            raise ParseError(
                f"暂不支持该文件类型: {suffix or 'unknown'}。支持: {', '.join(sorted(SUPPORTED_SUFFIXES))}"
            )

        safe_name = self._safe_filename(original_filename or source_path.name or f"upload{suffix or '.bin'}")
        return self._parse_path(
            request_id=uuid.uuid4().hex,
            input_path=source_path,
            original_filename=safe_name,
            lang=lang,
            device=device,
            parse_mode=parse_mode,
            save_artifacts=save_artifacts,
            include_raw_payload=include_raw_payload,
            use_chart_recognition=use_chart_recognition,
            use_formula_recognition=use_formula_recognition,
            use_seal_recognition=use_seal_recognition,
            use_table_recognition=use_table_recognition,
            include_asset_payloads=True,
        )

    def _parse_path(
        self,
        *,
        request_id: str,
        input_path: Path,
        original_filename: str,
        lang: str | None,
        device: str | None,
        parse_mode: str | None,
        save_artifacts: bool | None,
        include_raw_payload: bool | None,
        use_chart_recognition: bool,
        use_formula_recognition: bool,
        use_seal_recognition: bool,
        use_table_recognition: bool,
        include_asset_payloads: bool = False,
    ) -> RagParseData:
        """执行统一解析流程，并按需返回标准或 Rag 数据模型。"""

        resolved_lang = lang or self.settings.default_lang
        resolved_device = device or self.settings.default_device
        resolved_mode = (parse_mode or self.settings.default_parse_mode).strip().lower()
        resolved_save_artifacts = self.settings.default_save_artifacts if save_artifacts is None else save_artifacts
        resolved_include_raw = (
            self.settings.default_include_raw_payload if include_raw_payload is None else include_raw_payload
        )

        options = PipelineOptions(
            lang=resolved_lang,
            device=resolved_device,
            parse_mode=resolved_mode,
            use_chart_recognition=use_chart_recognition,
            use_formula_recognition=use_formula_recognition,
            use_seal_recognition=use_seal_recognition,
            use_table_recognition=use_table_recognition,
        )

        pipeline = self._get_pipeline(options)
        # 对文件进行归一化处理
        normalized_path, converted_from = self._normalize_source(input_path, request_id)

        # 准备输出目录
        request_output_dir = self.settings.output_root / request_id
        request_output_dir.mkdir(parents=True, exist_ok=True)
        stored_source_path = request_output_dir / input_path.name
        if input_path.resolve() != stored_source_path.resolve():
            shutil.copy2(input_path, stored_source_path)
        if normalized_path.resolve() != stored_source_path.resolve():
            normalized_copy = request_output_dir / normalized_path.name
            if normalized_path.resolve() != normalized_copy.resolve():
                shutil.copy2(normalized_path, normalized_copy)

        try:
            results: Iterable[Any] = pipeline.predict(str(normalized_path))
        except Exception as exc:  # pragma: no cover - depends on paddle runtime
            raise ParseError(f"PaddleOCR 解析失败: {exc}") from exc

        page_results = list(results)
        if not page_results:
            raise ParseError("解析结果为空")

        pages: list[PageArtifact] = []
        combined_markdown_parts: list[str] = []
        asset_paths: list[str] = []
        asset_payloads: list[RagAssetPayload] = []

        for index, result in enumerate(page_results):
            page_dir = request_output_dir / f"page-{index + 1:04d}"
            page_dir.mkdir(parents=True, exist_ok=True)

            raw_payload = self._extract_payload(result)
            markdown_bundle = self._extract_markdown_bundle(result)
            markdown_text = markdown_bundle["markdown_text"].strip()
            markdown_images = markdown_bundle["markdown_images"]
            block_count = self._count_blocks(raw_payload)

            if markdown_text:
                combined_markdown_parts.append(f"<!-- page:{index + 1} -->\n{markdown_text}")
            else:
                combined_markdown_parts.append(f"<!-- page:{index + 1} -->")

            markdown_path = None
            json_path = None
            if resolved_save_artifacts:
                # 写入 markdown 文本
                markdown_path = page_dir / "content.md"
                markdown_path.write_text(markdown_text, encoding="utf-8")

                # 处理 json 文本
                json_path = page_dir / "content.json"
                json_path.write_text(
                    json.dumps(raw_payload, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )

                # 处理 image 图像
                image_output_dir = page_dir / "images"
                saved_assets = self._persist_markdown_images(markdown_images, image_output_dir)
                asset_paths.extend(saved_assets)
            if include_asset_payloads:
                asset_payloads.extend(self._serialize_markdown_images(markdown_images, index + 1))

            pages.append(
                PageArtifact(
                    page_index=index + 1,
                    markdown_text=markdown_text,
                    markdown_path=to_posix(markdown_path),
                    json_path=to_posix(json_path),
                    image_count=len(markdown_images),
                    block_count=block_count,
                    raw_payload=raw_payload if resolved_include_raw else None,
                )
            )

        combined_markdown = "\n\n".join(part for part in combined_markdown_parts if part).strip()
        combined_markdown_path = None
        if resolved_save_artifacts:
            combined_markdown_path = request_output_dir / "document.md"
            combined_markdown_path.write_text(combined_markdown, encoding="utf-8")

        mime_type = mimetypes.guess_type(normalized_path.name)[0] or "application/octet-stream"
        summary = ParseSummary(
            parser="paddleocr-ppstructurev3" if resolved_mode == "structure" else "paddleocr-vl",
            parse_mode=resolved_mode,
            lang=resolved_lang,
            device=resolved_device,
            page_count=len(pages),
            converted_from=converted_from,
            stored_source_path=stored_source_path.as_posix(),
            normalized_source_path=normalized_path.as_posix(),
            combined_markdown_path=to_posix(combined_markdown_path),
        )
        metadata = {
            "request_output_dir": request_output_dir.as_posix(),
            "pipeline_options": options.__dict__,
        }

        return RagParseData(
            request_id=request_id,
            filename=original_filename,
            mime_type=mime_type,
            summary=summary,
            markdown=combined_markdown,
            assets=asset_payloads,
            metadata=metadata,
        )

    def _get_pipeline(self, options: PipelineOptions) -> Any:
        """按配置复用或懒加载底层 PaddleOCR pipeline。"""

        with self._cache_lock:
            cached = self._pipeline_cache.get(options)
            if cached is not None:
                return cached

            pipeline = self._build_pipeline(options)
            self._pipeline_cache[options] = pipeline
            return pipeline

    def _build_pipeline(self, options: PipelineOptions) -> Any:
        """根据解析模式构造 PaddleOCRVL 或 PPStructureV3 实例。"""

        try:
            from paddleocr import PaddleOCRVL, PPStructureV3  # type: ignore
        except Exception as exc:  # pragma: no cover - import depends on installed deps
            raise ParseError(
                "无法导入 paddleocr。请先安装 paddlepaddle/paddlepaddle-gpu 与 paddleocr。"
            ) from exc

        common_kwargs = {
            "device": options.device,
        }
        if options.parse_mode == "vl":
            try:
                # PaddleOCRVL 模式太慢了
                return PaddleOCRVL(
                    use_doc_orientation_classify=False,
                    use_doc_unwarping=False,
                    **common_kwargs,
                )
            except Exception as exc:
                raise RuntimeError(f"PaddleOCRVL 加载失败；err={exc}") from exc
        if options.parse_mode != "structure":
            raise ParseError(f"不支持的 parse_mode: {options.parse_mode}，可选: structure / vl")
        try:
            return PPStructureV3(
                lang=options.lang,
                use_chart_recognition=options.use_chart_recognition,
                use_formula_recognition=options.use_formula_recognition,
                use_seal_recognition=options.use_seal_recognition,
                use_table_recognition=options.use_table_recognition,
                **common_kwargs,
            )
        except Exception as exc:
            raise RuntimeError(f"PPStructureV3 加载失败；err={exc}") from exc

    def _normalize_source(self, input_path: Path, request_id: str) -> tuple[Path, str | None]:
        """将 Office 文档转换为 PDF，其余文件保持原样返回。"""

        suffix = input_path.suffix.lower()
        # 如果不是 office 直接返回源文件
        if suffix not in OFFICE_SUFFIXES:
            return input_path, None

        soffice_bin = self._resolve_soffice()
        if soffice_bin is None:
            raise ParseError(
                "检测到 Office 文档，但当前环境没有找到 LibreOffice/soffice。"
                " 请安装 LibreOffice，或设置 SOFFICE_BIN。"
            )

        convert_dir = self.settings.temp_root / request_id / "converted"
        convert_dir.mkdir(parents=True, exist_ok=True)
        command = [
            soffice_bin,
            "--headless",
            "--convert-to",
            "pdf",
            "--outdir",
            str(convert_dir),
            str(input_path),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
        except subprocess.CalledProcessError as exc:  # pragma: no cover - external dependency
            raise ParseError(
                f"LibreOffice 转 PDF 失败: {exc.stderr.strip() or exc.stdout.strip() or exc}"
            ) from exc

        pdf_path = convert_dir / f"{input_path.stem}.pdf"
        if not pdf_path.exists():
            raise ParseError("LibreOffice 转换完成，但未找到输出 PDF")
        return pdf_path, suffix

    def _resolve_soffice(self) -> str | None:
        """解析 LibreOffice `soffice` 可执行文件路径。"""

        if self.settings.soffice_bin and Path(self.settings.soffice_bin).exists():
            return self.settings.soffice_bin
        for candidate in ("soffice", "soffice.exe"):
            located = shutil.which(candidate)
            if located:
                return located
        return None

    def _extract_markdown_bundle(self, result: Any) -> dict[str, Any]:
        """从 Paddle 结果对象中抽取 markdown 文本与图片映射。"""

        markdown_obj = getattr(result, "markdown", None)
        if callable(markdown_obj):
            markdown_obj = markdown_obj()
        if isinstance(markdown_obj, dict):
            # PaddleX/PaddleOCR currently emits markdown under `markdown_texts`,
            # while some older wrappers use `markdown_text`.
            markdown_text = str(
                markdown_obj.get("markdown_texts", markdown_obj.get("markdown_text", "")) or ""
            )
            markdown_images = markdown_obj.get("markdown_images", {}) or {}
            if not isinstance(markdown_images, dict):
                markdown_images = {}
            return {
                "markdown_text": markdown_text,
                "markdown_images": markdown_images,
            }
        return {
            "markdown_text": "",
            "markdown_images": {},
        }

    def _extract_payload(self, result: Any) -> dict[str, Any]:
        """尽可能将底层解析结果归一化为可序列化字典。"""

        for attr_name in ("json", "res", "_res", "result"):
            candidate = getattr(result, attr_name, None)
            if callable(candidate):
                candidate = candidate()
            normalized = self._normalize_jsonable(candidate)
            if isinstance(normalized, dict):
                return normalized

        if hasattr(result, "model_dump"):
            normalized = self._normalize_jsonable(result.model_dump())
            if isinstance(normalized, dict):
                return normalized
        if hasattr(result, "to_dict"):
            normalized = self._normalize_jsonable(result.to_dict())
            if isinstance(normalized, dict):
                return normalized
        return {"repr": repr(result)}

    def _persist_markdown_images(self, markdown_images: dict[str, Any], output_dir: Path) -> list[str]:
        """将 markdown 中的图片资源写入磁盘并返回输出路径列表。"""

        if not markdown_images:
            return []
        output_dir.mkdir(parents=True, exist_ok=True)
        saved_paths: list[str] = []
        for index, (name, image) in enumerate(markdown_images.items(), start=1):
            materialized = self._materialize_markdown_image(name, image, index)
            if materialized is None:
                continue
            safe_name, _, content = materialized
            target_path = output_dir / safe_name
            target_path.write_bytes(content)
            saved_paths.append(target_path.as_posix())
        return saved_paths

    def _serialize_markdown_images(self, markdown_images: dict[str, Any], page_index: int) -> list[RagAssetPayload]:
        """将 markdown 图片转为适合通过 API 直接返回的 base64 载荷。"""

        serialized: list[RagAssetPayload] = []
        if not markdown_images:
            return serialized
        for index, (name, image) in enumerate(markdown_images.items(), start=1):
            materialized = self._materialize_markdown_image(name, image, index)
            if materialized is None:
                continue
            safe_name, media_type, content = materialized
            serialized.append(
                RagAssetPayload(
                    markdown_name=name or safe_name,
                    original_name=safe_name,
                    media_type=media_type,
                    content_base64=base64.b64encode(content).decode("ascii"),
                    page_index=page_index,
                )
            )
        return serialized

    def _count_blocks(self, payload: dict[str, Any]) -> int:
        """从不同版本的解析结果字段中估算版面块数量。"""

        if not payload:
            return 0
        for key in ("parsing_res_list", "layout_parsing_result", "layout_det_res", "blocks"):
            value = payload.get(key)
            if isinstance(value, list):
                return len(value)
        return 0

    def _normalize_jsonable(self, value: Any) -> Any:
        """递归清洗对象，确保其可以安全写入 JSON。"""

        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        if isinstance(value, Path):
            return value.as_posix()
        if isinstance(value, dict):
            return {str(k): self._normalize_jsonable(v) for k, v in value.items()}
        if isinstance(value, (list, tuple, set)):
            return [self._normalize_jsonable(item) for item in value]
        if isinstance(value, bytes):
            return {"type": "bytes", "length": len(value)}
        if hasattr(value, "tolist"):
            try:
                return value.tolist()
            except Exception:
                pass
        if hasattr(value, "save"):
            return {
                "type": value.__class__.__name__,
                "repr": repr(value),
            }
        if hasattr(value, "__dict__"):
            return {
                key: self._normalize_jsonable(val)
                for key, val in vars(value).items()
                if not key.startswith("_")
            }
        return str(value)

    def _safe_filename(self, filename: str) -> str:
        """生成适合在本地文件系统落盘的安全文件名。"""

        base = os.path.basename(filename).strip()
        sanitized = "".join(ch if ch.isalnum() or ch in {"-", "_", "."} else "_" for ch in base)
        return sanitized or "upload.bin"

    def _materialize_markdown_image(
        self,
        name: str,
        image: Any,
        fallback_index: int,
    ) -> tuple[str, str, bytes] | None:
        """将 markdown 图片统一转换为文件名、媒体类型与字节内容。"""

        safe_name = self._ensure_image_filename(name or f"image-{fallback_index}.png")
        if isinstance(image, (bytes, bytearray)):
            media_type = mimetypes.guess_type(safe_name)[0] or "application/octet-stream"
            return safe_name, media_type, bytes(image)

        try:
            from PIL import Image  # type: ignore
        except Exception:
            Image = None  # type: ignore

        pil_image = None
        if Image is not None and isinstance(image, Image.Image):  # pragma: no branch - runtime guarded
            pil_image = image
        elif hasattr(image, "save"):
            pil_image = image

        if pil_image is None:
            return None

        output = BytesIO()
        image_format = self._infer_image_format(safe_name)
        pil_image.save(output, format=image_format)
        media_type = mimetypes.guess_type(safe_name)[0] or self._mime_type_from_format(image_format)
        return safe_name, media_type, output.getvalue()

    def _ensure_image_filename(self, filename: str) -> str:
        """确保图片文件名包含受支持的后缀。"""

        safe_name = self._safe_filename(filename)
        suffix = Path(safe_name).suffix.lower()
        if suffix in {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".tif", ".tiff"}:
            return safe_name
        return f"{safe_name}.png"

    def _infer_image_format(self, filename: str) -> str:
        """根据文件后缀推断 Pillow 保存时使用的格式。"""

        suffix = Path(filename).suffix.lower()
        return {
            ".jpg": "JPEG",
            ".jpeg": "JPEG",
            ".png": "PNG",
            ".webp": "WEBP",
            ".bmp": "BMP",
            ".gif": "GIF",
            ".tif": "TIFF",
            ".tiff": "TIFF",
        }.get(suffix, "PNG")

    def _mime_type_from_format(self, image_format: str) -> str:
        """将 Pillow 图片格式名称映射为 MIME 类型。"""

        return {
            "JPEG": "image/jpeg",
            "PNG": "image/png",
            "WEBP": "image/webp",
            "BMP": "image/bmp",
            "GIF": "image/gif",
            "TIFF": "image/tiff",
        }.get(image_format.upper(), "application/octet-stream")
