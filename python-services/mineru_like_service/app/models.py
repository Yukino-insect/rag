"""MinerU-like service 的请求与响应数据模型。"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, Field

class PageArtifact(BaseModel):
    """保存单页解析结果及其落盘产物信息。"""

    page_index: int
    markdown_text: str
    markdown_path: str | None = None
    json_path: str | None = None
    image_count: int = 0
    block_count: int = 0
    raw_payload: dict[str, Any] | None = None


class ParseSummary(BaseModel):
    """汇总一次解析任务的核心元信息。"""

    parser: str
    parse_mode: str
    lang: str
    device: str
    page_count: int
    converted_from: str | None = None
    stored_source_path: str
    normalized_source_path: str
    combined_markdown_path: str | None = None


class ParseData(BaseModel):
    """标准解析接口返回的完整文档结果。"""

    request_id: str
    filename: str
    mime_type: str
    summary: ParseSummary
    markdown: str
    pages: list[PageArtifact]
    assets: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class RagAssetPayload(BaseModel):
    """面向 Rag 输出的内联资源载荷。"""

    markdown_name: str
    original_name: str
    media_type: str
    content_base64: str
    page_index: int | None = None


class RagParseData(BaseModel):
    """Rag 场景下使用的解析结果模型。"""

    request_id: str
    filename: str
    mime_type: str
    summary: ParseSummary
    markdown: str
    assets: list[RagAssetPayload] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class RagTaskSubmitData(BaseModel):
    """异步任务提交结果。"""

    task_id: str
    status: str
    doc_id: str | None = None


class RagTaskResultData(BaseModel):
    """异步任务状态与结果。"""

    task_id: str
    status: str
    doc_id: str | None = None
    request_id: str | None = None
    duration_ms: int | None = None
    error: str | None = None
    result: RagParseData | None = None


class RagTaskCallbackPayload(BaseModel):
    """Python 回调 Java 时发送的任务通知体。"""

    task_id: str
    status: str
    doc_id: str | None = None
    request_id: str | None = None
    duration_ms: int | None = None
    error: str | None = None

class RagTaskStatusData(BaseModel):
    """" Python 服务反馈 Java 服务任务状态 """

    task_id: str
    status: str
    doc_id: str | None = None

T = TypeVar('T')

class ApiResponse(BaseModel, Generic[T]):
    """统一的 API 响应包装结构。"""

    success: bool
    data: T | None = None
    error: str | None = None


class RuntimeStatus(BaseModel):
    """服务运行时状态与目录配置快照。"""

    service_name: str
    service_version: str
    healthy: bool
    pipeline_cache_size: int
    output_root: str
    temp_root: str
    task_max_workers: int
    task_max_queue_size: int
    queued_tasks: int
    running_tasks: int
    completed_tasks: int
    failed_tasks: int
    rejected_tasks: int
    rate_limit_enabled: bool
    rate_limit_rps: float
    rate_limit_burst: int


def to_posix(path: Path | None) -> str | None:
    """将 `Path` 转为 POSIX 风格字符串，便于 API 返回。"""

    return None if path is None else path.as_posix()
