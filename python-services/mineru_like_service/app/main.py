from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

try:
    from .config import load_settings
    from .governance import TokenBucketRateLimiter
    from .models import ApiResponse, RuntimeStatus, RagTaskResultData, RagTaskStatusData, RagTaskSubmitData
    from .pipeline import MinerULikeParserService, ParseError
    from .task_manager import AsyncRagTaskManager, TaskQueueFullError
except ImportError:  # pragma: no cover - compatible with direct script execution
    from config import load_settings
    from governance import TokenBucketRateLimiter
    from models import ApiResponse, RuntimeStatus, RagTaskResultData, RagTaskStatusData, RagTaskSubmitData
    from pipeline import MinerULikeParserService, ParseError
    from task_manager import AsyncRagTaskManager, TaskQueueFullError

logger = logging.getLogger("uvicorn.error")
settings = load_settings()
parser_service = MinerULikeParserService(settings)
task_manager = AsyncRagTaskManager(settings, parser_service)
rate_limiter = TokenBucketRateLimiter(settings.rate_limit_rps, settings.rate_limit_burst)


async def _persist_upload_file(upload: UploadFile, destination: Path, max_bytes: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with destination.open("wb") as output:
        while True:
            chunk = await upload.read(1024 * 1024)
            if not chunk:
                break
            written += len(chunk)
            if written > max_bytes:
                raise HTTPException(status_code=413, detail=f"文件超过限制: {settings.max_upload_mb} MB")
            output.write(chunk)

@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    task_manager.shutdown()
app = FastAPI(
    title="MinerU-like Parser Service",
    version=settings.service_version,
    description="Standalone MinerU-like parsing service powered by PaddleOCR PP-StructureV3",
    lifespan=lifespan
)

@app.middleware("http")
async def rate_limit_middleware(request, call_next):
    if request.url.path not in {"/health", "/version"} and not rate_limiter.allow():
        retry_after = rate_limiter.retry_after_seconds()
        return JSONResponse(
            status_code=429,
            headers={"Retry-After": str(retry_after)},
            content=ApiResponse(success=False, error="too many requests").model_dump(),
        )
    return await call_next(request)


@app.get("/health", response_model=RuntimeStatus)
def health() -> RuntimeStatus:
    stats = task_manager.stats()
    return RuntimeStatus(
        service_name=settings.service_name,
        service_version=settings.service_version,
        healthy=True,
        pipeline_cache_size=parser_service.pipeline_cache_size,
        output_root=settings.output_root.as_posix(),
        temp_root=settings.temp_root.as_posix(),
        task_max_workers=settings.task_max_workers,
        task_max_queue_size=settings.task_max_queue_size,
        queued_tasks=stats["queued_tasks"],
        running_tasks=stats["running_tasks"],
        completed_tasks=stats["completed_tasks"],
        failed_tasks=stats["failed_tasks"],
        rejected_tasks=stats["rejected_tasks"],
        rate_limit_enabled=rate_limiter.enabled,
        rate_limit_rps=settings.rate_limit_rps,
        rate_limit_burst=settings.rate_limit_burst,
    )


@app.get("/version")
def version() -> dict[str, str]:
    return {
        "service_name": settings.service_name,
        "service_version": settings.service_version,
        "default_parse_mode": settings.default_parse_mode,
    }

@app.post("/tasks/rag", response_model=ApiResponse)
async def submit_rag_task(
    file: UploadFile = File(...),
    callback_url: str = Form(...),
    lang: str = Form("ch"),
    device: str = Form("cpu"),
    parse_mode: str = Form("auto"),
    save_artifacts: bool = Form(True),
    include_raw_payload: bool = Form(False),
    use_chart_recognition: bool = Form(False),
    use_formula_recognition: bool = Form(False),
    use_seal_recognition: bool = Form(False),
    use_table_recognition: bool = Form(False),
    doc_id: str | None = Form(None),
) -> ApiResponse[RagTaskSubmitData]:
    file_name = file.filename
    if file_name is None:
        raise ParseError("没有正确的文件名称")
    upload_path = task_manager.prepare_upload_path(file_name)
    try:
        # 将文件持久化到磁盘
        await _persist_upload_file(file, upload_path, settings.max_upload_mb * 1024 * 1024)
        data = task_manager.submit_file_for_rag(
            original_filename=file_name,
            source_path=upload_path,
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
        upload_path = None
    except Exception:
        if upload_path is not None:
            task_manager.cleanup_upload(upload_path)
        raise
    finally:
        await file.close()

    return ApiResponse(success=True, data=data)


@app.exception_handler(TaskQueueFullError)
async def task_queue_full_handler(_, exc: TaskQueueFullError) -> JSONResponse:
    logger.warning("任务队列已满: %s", exc)
    return JSONResponse(
        status_code=429,
        headers={"Retry-After": "1"},
        content=ApiResponse(success=False, error=str(exc)).model_dump(),
    )

@app.get("/tasks/{task_id}/result", response_model=ApiResponse)
def get_task_result(task_id: str) -> ApiResponse[RagTaskResultData]:
    data = task_manager.get_result(task_id)
    if data is None:
        raise HTTPException(status_code=404, detail="task not found")
    return ApiResponse(success=True, data=data)

@app.get("/tasks/{task_id}/status", response_model=ApiResponse)
def get_task_status(task_id: str) -> ApiResponse[RagTaskStatusData]:
    data = task_manager.get_task_status(task_id)
    if data is None:
        raise HTTPException(status_code=404, detail="task not found")
    return ApiResponse(success=True, data=data)

@app.exception_handler(HTTPException)
async def http_exception_handler(_, exc: HTTPException) -> JSONResponse:
    logger.warning("捕获 HTTP 异常 [StatusCode %s]: %s", exc.status_code, exc.detail)
    return JSONResponse(
        status_code=exc.status_code,
        content=ApiResponse(success=False, error=str(exc.detail)).model_dump(),
    )


@app.exception_handler(Exception)
async def generic_exception_handler(_, exc: Exception) -> JSONResponse:
    logger.exception("服务发生未捕获异常: %s", exc)
    return JSONResponse(
        status_code=500,
        content=ApiResponse(success=False, error=f"服务异常: {exc}").model_dump(),
    )

if __name__ == "__main__":
    uvicorn.run(app, host=settings.host, port=settings.port)
