"""MinerU-like 异步任务管理器。"""

from __future__ import annotations

import hashlib
import hmac
import json
import shutil
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

try:
    from .config import ServiceSettings
    from .models import (
        RagParseData,
        RagTaskCallbackPayload,
        RagTaskResultData,
        RagTaskSubmitData,
        RagTaskStatusData,
    )
    from .pipeline import MinerULikeParserService
except ImportError:  # pragma: no cover - compatible with direct script execution
    from config import ServiceSettings
    from models import (
        RagParseData,
        RagTaskCallbackPayload,
        RagTaskResultData,
        RagTaskSubmitData,
        RagTaskStatusData,
    )
    from pipeline import MinerULikeParserService


class TaskQueueFullError(RuntimeError):
    """Raised when the local async task queue is full."""


class AsyncRagTaskManager:
    """封装异步提交、状态落盘、结果拉取与回调通知。"""

    def __init__(self, settings: ServiceSettings, parser_service: MinerULikeParserService):
        """初始化任务管理器并准备线程池与状态目录。"""
        self.settings = settings
        self.parser_service = parser_service
        self._lock = threading.Lock()
        self._stats_lock = threading.Lock()
        self._capacity = threading.BoundedSemaphore(settings.task_max_workers + settings.task_max_queue_size)
        self._queued_tasks = 0
        self._running_tasks = 0
        self._completed_tasks = 0
        self._failed_tasks = 0
        self._rejected_tasks = 0
        self._executor = ThreadPoolExecutor(
            max_workers=settings.task_max_workers,
            thread_name_prefix="mineru-task",
        )
        self.settings.temp_root.mkdir(parents=True, exist_ok=True)
        self.settings.task_state_root.mkdir(parents=True, exist_ok=True)

    def shutdown(self) -> None:
        """关闭内部线程池，不再接受新的异步任务。"""
        self._executor.shutdown(wait=False, cancel_futures=False)

    def stats(self) -> dict[str, int]:
        """Return process-local queue/runtime counters."""
        with self._stats_lock:
            return {
                "queued_tasks": self._queued_tasks,
                "running_tasks": self._running_tasks,
                "completed_tasks": self._completed_tasks,
                "failed_tasks": self._failed_tasks,
                "rejected_tasks": self._rejected_tasks,
            }

    def prepare_upload_path(self, original_filename: str) -> Path:
        safe_name = Path(original_filename or "upload.bin").name or "upload.bin"
        upload_dir = self.settings.temp_root / "incoming" / uuid.uuid4().hex
        upload_dir.mkdir(parents=True, exist_ok=True)
        return upload_dir / safe_name

    def cleanup_upload(self, source_path: Path) -> None:
        try:
            shutil.rmtree(source_path.parent, ignore_errors=True)
        except Exception:
            pass

    def submit_upload_for_rag(
        self,
        *,
        original_filename: str,
        payload: bytes,
        doc_id: str | None,
        callback_url: str | None,
        lang: str | None,
        device: str | None,
        parse_mode: str | None,
        save_artifacts: bool | None,
        include_raw_payload: bool | None,
        use_chart_recognition: bool,
        use_formula_recognition: bool,
        use_seal_recognition: bool,
        use_table_recognition: bool,
    ) -> RagTaskSubmitData:
        """提交上传解析任务并返回初始任务信息。"""
        source_path = self.prepare_upload_path(original_filename)
        source_path.write_bytes(payload)
        try:
            return self.submit_file_for_rag(
                original_filename=original_filename,
                source_path=source_path,
                doc_id=doc_id,
                callback_url=callback_url,
                lang=lang,
                device=device,
                parse_mode=parse_mode,
                save_artifacts=save_artifacts,
                include_raw_payload=include_raw_payload,
                use_chart_recognition=use_chart_recognition,
                use_formula_recognition=use_formula_recognition,
                use_seal_recognition=use_seal_recognition,
                use_table_recognition=use_table_recognition,
            )
        except Exception:
            self.cleanup_upload(source_path)
            raise

    def submit_file_for_rag(
        self,
        *,
        original_filename: str,
        source_path: Path,
        doc_id: str | None,
        callback_url: str | None,
        lang: str | None,
        device: str | None,
        parse_mode: str | None,
        save_artifacts: bool | None,
        include_raw_payload: bool | None,
        use_chart_recognition: bool,
        use_formula_recognition: bool,
        use_seal_recognition: bool,
        use_table_recognition: bool,
    ) -> RagTaskSubmitData:
        """提交已落盘文件解析任务并返回初始任务信息。"""
        if not self._capacity.acquire(blocking=False):
            self._inc_stat("_rejected_tasks", 1)
            raise TaskQueueFullError(
                f"task queue is full; max_workers={self.settings.task_max_workers}, "
                f"max_queue_size={self.settings.task_max_queue_size}"
            )

        task_id = uuid.uuid4().hex
        state = {
            "task_id": task_id,
            "status": "submitted",
            "doc_id": doc_id,
            "callback_url": callback_url,
            "created_at": self._now_ms(),
            "updated_at": self._now_ms(),
            "request_id": None,
            "duration_ms": None,
            "error": None,
            "result_path": None,
            "callback_attempts": 0,
            "callback_success": False,
            "last_callback_error": None,
        }
        queued = False
        try:
            self._write_state(task_id, state)
            self._inc_stat("_queued_tasks", 1)
            queued = True
            self._executor.submit(
                self._run_file_task,
                task_id,
                original_filename,
                source_path,
                callback_url,
                lang,
                device,
                parse_mode,
                save_artifacts,
                include_raw_payload,
                use_chart_recognition,
                use_formula_recognition,
                use_seal_recognition,
                use_table_recognition,
            )
        except Exception:
            if queued:
                self._inc_stat("_queued_tasks", -1)
            self._capacity.release()
            raise
        return RagTaskSubmitData(task_id=task_id, status="submitted", doc_id=doc_id)

    def get_result(self, task_id: str) -> RagTaskResultData | None:
        """按任务 ID 读取状态与落盘结果，不存在时返回空。"""
        state = self._read_state(task_id)
        if state is None:
            return None

        result = None
        result_path = state.get("result_path")
        if result_path:
            path = Path(result_path)
            if path.exists():
                result = RagParseData.model_validate_json(path.read_text(encoding="utf-8"))

        return RagTaskResultData(
            task_id=task_id,
            status=str(state.get("status") or ""),
            doc_id=state.get("doc_id"),
            request_id=state.get("request_id"),
            duration_ms=state.get("duration_ms"),
            error=state.get("error"),
            result=result,
        )

    def _run_file_task(
        self,
        task_id: str,
        original_filename: str,
        source_path: Path,
        callback_url: str | None,
        lang: str | None,
        device: str | None,
        parse_mode: str | None,
        save_artifacts: bool | None,
        include_raw_payload: bool | None,
        use_chart_recognition: bool,
        use_formula_recognition: bool,
        use_seal_recognition: bool,
        use_table_recognition: bool,
    ) -> None:
        """在线程池中执行上传解析任务，并在结束后更新状态与回调。"""
        self._mark_task_started()
        started_at = self._now_ms()
        # 更新任务状态为 running
        self._update_state(task_id, status="running", started_at=started_at, updated_at=started_at)

        success = False
        try:
            result = self.parser_service.parse_file_for_rag(
                original_filename=original_filename,
                source_path=source_path,
                lang=lang,
                device=device,
                parse_mode=parse_mode,
                save_artifacts=save_artifacts,
                include_raw_payload=include_raw_payload,
                use_chart_recognition=use_chart_recognition,
                use_formula_recognition=use_formula_recognition,
                use_seal_recognition=use_seal_recognition,
                use_table_recognition=use_table_recognition,
            )
            finished_at = self._now_ms()
            duration_ms = max(0, finished_at - started_at)
            result_path = self._write_result(task_id, result)
            self._update_state(
                task_id,
                status="success",
                request_id=result.request_id,
                duration_ms=duration_ms,
                result_path=result_path.as_posix(),
                error=None,
                completed_at=finished_at,
                updated_at=finished_at,
            )
            success = True
        except Exception as exc:
            finished_at = self._now_ms()
            duration_ms = max(0, finished_at - started_at)
            self._update_state(
                task_id,
                status="failed",
                duration_ms=duration_ms,
                error=str(exc),
                completed_at=finished_at,
                updated_at=finished_at,
            )
        finally:
            self.cleanup_upload(source_path)
            if callback_url:
                self._notify_callback(task_id, callback_url)
            self._mark_task_finished(success)

    def get_task_status(self, task_id: str) -> RagTaskStatusData | None:
        """获取当前任务状态"""
        status = self._read_state(task_id)
        if status is None:
            return None
        return RagTaskStatusData(
            task_id=task_id,
            status=str(status.get("status") or ""),
            doc_id=status.get("doc_id"),
        )

    def _notify_callback(self, task_id: str, callback_url: str) -> None:
        """根据任务当前状态向回调地址发送通知，并按配置重试。"""
        state = self._read_state(task_id)
        if state is None:
            return

        payload = RagTaskCallbackPayload(
            task_id=task_id,
            status=str(state.get("status") or ""),
            doc_id=state.get("doc_id"),
            request_id=state.get("request_id"),
            duration_ms=state.get("duration_ms"),
            error=state.get("error"),
        )
        body = json.dumps(payload.model_dump(exclude_none=True), ensure_ascii=False).encode("utf-8")

        last_error: str | None = None
        for attempt in range(1, self.settings.callback_max_retries + 1):
            try:
                self._post_callback(callback_url, body)
                self._update_state(
                    task_id,
                    callback_attempts=attempt,
                    callback_success=True,
                    last_callback_error=None,
                    updated_at=self._now_ms(),
                )
                return
            except Exception as exc:
                last_error = str(exc)
                self._update_state(
                    task_id,
                    callback_attempts=attempt,
                    callback_success=False,
                    last_callback_error=last_error,
                    updated_at=self._now_ms(),
                )
                if attempt < self.settings.callback_max_retries:
                    time.sleep(self.settings.callback_retry_delay_ms / 1000.0)

        if last_error:
            self._update_state(task_id, error=state.get("error") or last_error, updated_at=self._now_ms())

    def _post_callback(self, callback_url: str, body: bytes) -> None:
        """向指定回调地址发送 HTTP POST，并在失败时抛出统一异常。"""
        timestamp = str(int(time.time()))
        headers = {
            "Content-Type": "application/json",
            "X-MinerU-Timestamp": timestamp,
        }
        if self.settings.callback_secret:
            signature = hmac.new(
                self.settings.callback_secret.encode("utf-8"),
                timestamp.encode("utf-8") + b"\n" + body,
                hashlib.sha256,
            ).hexdigest()
            headers["X-MinerU-Signature"] = signature

        request = urllib.request.Request(
            callback_url,
            data=body,
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.settings.callback_timeout_seconds) as response:
                if response.status < 200 or response.status >= 300:
                    raise RuntimeError(f"callback status={response.status}")
        except urllib.error.HTTPError as exc:  # pragma: no cover - depends on remote service
            raise RuntimeError(f"callback http error={exc.code}") from exc
        except urllib.error.URLError as exc:  # pragma: no cover - depends on remote service
            raise RuntimeError(f"callback network error={exc.reason}") from exc

    def _write_result(self, task_id: str, result: RagParseData) -> Path:
        """将解析结果写入请求输出目录，并返回结果文件路径。"""
        request_output_dir = Path(str(result.metadata.get("request_output_dir") or self.settings.output_root / task_id))
        request_output_dir.mkdir(parents=True, exist_ok=True)
        result_path = request_output_dir / "rag_task_result.json"
        result_path.write_text(result.model_dump_json(indent=2), encoding="utf-8")
        return result_path

    def _state_path(self, task_id: str) -> Path:
        """返回任务状态文件的固定落盘路径。"""
        return self.settings.task_state_root / f"{task_id}.json"

    def _read_state(self, task_id: str) -> dict[str, Any] | None:
        """读取任务状态文件并反序列化为字典。"""
        state_path = self._state_path(task_id)
        if not state_path.exists():
            return None
        return json.loads(state_path.read_text(encoding="utf-8"))

    def _write_state(self, task_id: str, state: dict[str, Any]) -> None:
        """在加锁条件下写入任务状态，避免并发覆盖。"""
        state_path = self._state_path(task_id)
        with self._lock:
            self._replace_json(state_path, state)

    def _update_state(self, task_id: str, **changes: Any) -> None:
        """读取现有状态后合并变更，并重新写回状态文件。"""
        with self._lock:
            state_path = self._state_path(task_id)
            if state_path.exists():
                state = json.loads(state_path.read_text(encoding="utf-8"))
            else:
                state = {"task_id": task_id}
            state.update(changes)
            if "updated_at" not in changes:
                state["updated_at"] = self._now_ms()
            self._replace_json(state_path, state)

    def _replace_json(self, state_path: Path, state: dict[str, Any]) -> None:
        tmp_path = state_path.with_name(f"{state_path.name}.{uuid.uuid4().hex}.tmp")
        tmp_path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp_path.replace(state_path)

    def _mark_task_started(self) -> None:
        with self._stats_lock:
            self._queued_tasks = max(0, self._queued_tasks - 1)
            self._running_tasks += 1

    def _mark_task_finished(self, success: bool) -> None:
        with self._stats_lock:
            self._running_tasks = max(0, self._running_tasks - 1)
            if success:
                self._completed_tasks += 1
            else:
                self._failed_tasks += 1
        self._capacity.release()

    def _inc_stat(self, attr: str, delta: int) -> None:
        with self._stats_lock:
            setattr(self, attr, max(0, getattr(self, attr) + delta))

    @staticmethod
    def _now_ms() -> int:
        """返回当前 Unix 时间戳，单位为毫秒。"""
        return int(time.time() * 1000)
