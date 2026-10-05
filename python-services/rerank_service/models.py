from pydantic import BaseModel


class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    top_n: int | None = None # 可选参数，返回前 N 个最高文档，若不传则返回全部

class RerankResultItem(BaseModel):
    index: int
    document: str
    score: float

class RerankResponse(BaseModel):
    success: bool
    results: list[RerankResultItem]
    err: str | None = None


class RuntimeStatus(BaseModel):
    service_name: str
    service_version: str
    model_name: str
    healthy: bool
    max_workers: int
    max_queue_size: int
    queued_requests: int
    running_requests: int
    completed_requests: int
    failed_requests: int
    rejected_requests: int
    timed_out_requests: int
    rate_limit_enabled: bool
    rate_limit_rps: float
    rate_limit_burst: int
