import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

try:
    from .config import RerankServiceSetting
    from .governance import TokenBucketRateLimiter
    from .models import RerankRequest, RerankResponse, RuntimeStatus
    from .task_manager import RerankQueueFullError, RerankTimeoutError, TaskManager
except ImportError:  # pragma: no cover - 兼容直接脚本启动
    from config import RerankServiceSetting
    from governance import TokenBucketRateLimiter
    from models import RerankRequest, RerankResponse, RuntimeStatus
    from task_manager import RerankQueueFullError, RerankTimeoutError, TaskManager

settings = RerankServiceSetting()
rate_limiter = TokenBucketRateLimiter(settings.rate_limit_rps, settings.rate_limit_burst)

@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    task_manager.shutdown()
app = FastAPI(
    title="BGE Reranker API Service",
    description="based BAAI/bge-reranker-v2-m3 rerank service",
    version=settings.service_version,
    lifespan=lifespan
)
task_manager = TaskManager(settings)
logger = logging.getLogger("uvicorn.error")
logger.setLevel(logging.INFO)

@app.get("/health")
def health() -> RuntimeStatus:
    stats = task_manager.stats()
    return RuntimeStatus(
        service_name=settings.service_name,
        service_version=settings.service_version,
        model_name=settings.model_name,
        healthy=True,
        max_workers=settings.max_workers,
        max_queue_size=settings.max_queue_size,
        queued_requests=stats["queued_requests"],
        running_requests=stats["running_requests"],
        completed_requests=stats["completed_requests"],
        failed_requests=stats["failed_requests"],
        rejected_requests=stats["rejected_requests"],
        timed_out_requests=stats["timed_out_requests"],
        rate_limit_enabled=rate_limiter.enabled,
        rate_limit_rps=settings.rate_limit_rps,
        rate_limit_burst=settings.rate_limit_burst,
    )


@app.middleware("http")
async def rate_limit_middleware(request, call_next):
    if request.url.path != "/health" and not rate_limiter.allow():
        retry_after = rate_limiter.retry_after_seconds()
        return JSONResponse(
            status_code=429,
            headers={"Retry-After": str(retry_after)},
            content=RerankResponse(success=False, results=[], err="too many requests").model_dump(),
        )
    return await call_next(request)

@app.post("/rerank", response_model=RerankResponse)
def rerank_documents(request: RerankRequest):
    if not request.documents:
        raise HTTPException(status_code=400, detail="No documents provided")
    if len(request.query) > settings.max_query_chars:
        raise HTTPException(status_code=413, detail=f"Query too large; max={settings.max_query_chars} chars")
    if len(request.documents) > settings.max_documents:
        raise HTTPException(status_code=413, detail=f"Too many documents; max={settings.max_documents}")
    too_large_index = next(
        (index for index, document in enumerate(request.documents) if len(document) > settings.max_document_chars),
        None,
    )
    if too_large_index is not None:
        raise HTTPException(
            status_code=413,
            detail=f"Document too large at index={too_large_index}; max={settings.max_document_chars} chars",
        )

    # 计算所有配对的匹配分数
    items = task_manager.submit(request.query, request.documents, request.top_n)

    return RerankResponse(success=True, results=items)


@app.exception_handler(RerankQueueFullError)
async def queue_full_handler(_, exc: RerankQueueFullError) -> JSONResponse:
    logger.warning("rerank queue full: %s", exc)
    return JSONResponse(
        status_code=429,
        headers={"Retry-After": "1"},
        content=RerankResponse(success=False, results=[], err=str(exc)).model_dump(),
    )


@app.exception_handler(RerankTimeoutError)
async def timeout_handler(_, exc: RerankTimeoutError) -> JSONResponse:
    logger.warning("rerank timeout: %s", exc)
    return JSONResponse(
        status_code=504,
        content=RerankResponse(success=False, results=[], err=str(exc)).model_dump(),
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(_, exc: HTTPException) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content=RerankResponse(success=False, results=[], err=str(exc.detail)).model_dump(),
    )

if __name__ == "__main__":
    uvicorn.run(app, host=settings.host, port=settings.port)
