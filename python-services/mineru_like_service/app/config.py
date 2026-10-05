from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _bool_env(name: str, default: bool) -> bool:
    """
    读取布尔型环境变量的辅助函数
    """
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


def _resolve_service_root() -> Path:
    """
    推断当前服务项目根目录
    """
    return Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class ServiceSettings:
    service_name: str
    service_version: str
    host: str
    port: int
    output_root: Path
    temp_root: Path
    task_state_root: Path
    max_upload_mb: int
    default_lang: str
    default_device: str
    default_parse_mode: str
    default_save_artifacts: bool
    default_include_raw_payload: bool
    soffice_bin: str | None
    task_max_workers: int
    task_max_queue_size: int
    rate_limit_rps: float
    rate_limit_burst: int
    callback_secret: str | None
    callback_timeout_seconds: int
    callback_max_retries: int
    callback_retry_delay_ms: int


def load_settings() -> ServiceSettings:
    service_root = _resolve_service_root()
    output_root = Path(os.getenv("MINERU_LIKE_OUTPUT_ROOT", service_root / ".runtime" / "output"))
    temp_root = Path(os.getenv("MINERU_LIKE_TEMP_ROOT", service_root / ".runtime" / "temp"))
    task_state_root = Path(os.getenv("MINERU_LIKE_TASK_STATE_ROOT", service_root / ".runtime" / "tasks"))
    return ServiceSettings(
        service_name=os.getenv("MINERU_LIKE_SERVICE_NAME", "mineru-like-service"),
        service_version=os.getenv("MINERU_LIKE_SERVICE_VERSION", "0.1.0"),
        host=os.getenv("MINERU_LIKE_HOST", "0.0.0.0"),
        port=int(os.getenv("MINERU_LIKE_PORT", "8091")),
        output_root=output_root,
        temp_root=temp_root,
        task_state_root=task_state_root,
        max_upload_mb=_int_env("MINERU_LIKE_MAX_UPLOAD_MB", 100, 1),
        default_lang=os.getenv("MINERU_LIKE_DEFAULT_LANG", "ch"),
        default_device=os.getenv("MINERU_LIKE_DEFAULT_DEVICE", "cpu"),
        default_parse_mode=os.getenv("MINERU_LIKE_DEFAULT_PARSE_MODE", "structure"),
        default_save_artifacts=_bool_env("MINERU_LIKE_DEFAULT_SAVE_ARTIFACTS", True),
        default_include_raw_payload=_bool_env("MINERU_LIKE_DEFAULT_INCLUDE_RAW_PAYLOAD", False),
        soffice_bin=os.getenv("SOFFICE_BIN"),
        task_max_workers=_int_env("MINERU_LIKE_TASK_MAX_WORKERS", 2, 1),
        task_max_queue_size=_int_env("MINERU_LIKE_TASK_MAX_QUEUE_SIZE", 16, 0),
        rate_limit_rps=_float_env("MINERU_LIKE_RATE_LIMIT_RPS", 5.0, 0.0),
        rate_limit_burst=_int_env("MINERU_LIKE_RATE_LIMIT_BURST", 10, 0),
        callback_secret=os.getenv("MINERU_LIKE_CALLBACK_SECRET", "Yukino"),
        callback_timeout_seconds=_int_env("MINERU_LIKE_CALLBACK_TIMEOUT_SECONDS", 10, 3),
        callback_max_retries=_int_env("MINERU_LIKE_CALLBACK_MAX_RETRIES", 3, 1),
        callback_retry_delay_ms=_int_env("MINERU_LIKE_CALLBACK_RETRY_DELAY_MS", 1000, 100),
    )
