from __future__ import annotations

import os
from dataclasses import dataclass


def _bool_env(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int_env(name: str, default: int, minimum: int | None = None) -> int:
    value = int(os.getenv(name, str(default)))
    if minimum is not None:
        return max(minimum, value)
    return value


def _float_env(name: str, default: float, minimum: float | None = None) -> float:
    value = float(os.getenv(name, str(default)))
    if minimum is not None:
        return max(minimum, value)
    return value


def _auto_use_fp16() -> bool:
    raw = os.getenv("RERANK_USE_FP16")
    if raw is not None:
        return _bool_env("RERANK_USE_FP16", False)
    try:
        import torch

        return bool(torch.cuda.is_available())
    except Exception:
        return False


@dataclass(frozen=True)
class RerankServiceSetting:
    service_name: str = os.getenv("RERANK_SERVICE_NAME", "rerank-service")
    service_version: str = os.getenv("RERANK_SERVICE_VERSION", "1.0.0")
    host: str = os.getenv("RERANK_HOST", "0.0.0.0")
    port: int = _int_env("RERANK_PORT", 8092, 1)
    model_name: str = os.getenv("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3")
    use_fp16: bool = _auto_use_fp16()
    max_workers: int = _int_env("RERANK_MAX_WORKERS", 2, 1)
    max_queue_size: int = _int_env("RERANK_MAX_QUEUE_SIZE", 32, 0)
    request_timeout_seconds: int = _int_env("RERANK_REQUEST_TIMEOUT_SECONDS", 60, 1)
    rate_limit_rps: float = _float_env("RERANK_RATE_LIMIT_RPS", 20.0, 0.0)
    rate_limit_burst: int = _int_env("RERANK_RATE_LIMIT_BURST", 40, 0)
    max_documents: int = _int_env("RERANK_MAX_DOCUMENTS", 128, 1)
    max_query_chars: int = _int_env("RERANK_MAX_QUERY_CHARS", 4096, 1)
    max_document_chars: int = _int_env("RERANK_MAX_DOCUMENT_CHARS", 8192, 1)
