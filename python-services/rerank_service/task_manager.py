from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError

try:
    from .config import RerankServiceSetting
    from .models import RerankResultItem
    from .rerank_service import RerankService
except ImportError:  # pragma: no cover - 兼容直接脚本启动
    from config import RerankServiceSetting
    from models import RerankResultItem
    from rerank_service import RerankService


class RerankQueueFullError(RuntimeError):
    """Raised when the rerank request queue is full."""


class RerankTimeoutError(RuntimeError):
    """Raised when a rerank request waits longer than configured timeout."""


class TaskManager:

    def __init__(self, setting: RerankServiceSetting | None = None):
        self.setting = setting or RerankServiceSetting()
        self.rerank_service = RerankService(self.setting)
        self._capacity = threading.BoundedSemaphore(self.setting.max_workers + self.setting.max_queue_size)
        self._stats_lock = threading.Lock()
        self._queued_requests = 0
        self._running_requests = 0
        self._completed_requests = 0
        self._failed_requests = 0
        self._rejected_requests = 0
        self._timed_out_requests = 0
        self._executor = ThreadPoolExecutor(
            max_workers=self.setting.max_workers,
            thread_name_prefix="rerank-request",
        )

    def shutdown(self):
        self._executor.shutdown(wait=False, cancel_futures=False)

    def stats(self) -> dict[str, int]:
        with self._stats_lock:
            return {
                "queued_requests": self._queued_requests,
                "running_requests": self._running_requests,
                "completed_requests": self._completed_requests,
                "failed_requests": self._failed_requests,
                "rejected_requests": self._rejected_requests,
                "timed_out_requests": self._timed_out_requests,
            }

    def submit(self, query: str, docs: list[str], top_n: int | None) -> list[RerankResultItem]:
        if not self._capacity.acquire(blocking=False):
            self._inc_stat("_rejected_requests", 1)
            raise RerankQueueFullError(
                f"rerank queue is full; max_workers={self.setting.max_workers}, "
                f"max_queue_size={self.setting.max_queue_size}"
            )

        queued = False
        try:
            self._inc_stat("_queued_requests", 1)
            queued = True
            future = self._executor.submit(self._run_rerank, query, docs, top_n)
        except Exception:
            if queued:
                self._inc_stat("_queued_requests", -1)
            self._capacity.release()
            raise

        try:
            return future.result(timeout=self.setting.request_timeout_seconds)
        except TimeoutError as exc:
            self._inc_stat("_timed_out_requests", 1)
            if future.cancel():
                self._mark_cancelled_before_start()
            raise RerankTimeoutError("rerank request timed out") from exc

    def _run_rerank(self, query: str, docs: list[str], top_n: int | None) -> list[RerankResultItem]:
        self._mark_started()
        success = False
        try:
            result = self.rerank_service.rerank(
                query=query,
                docs=docs,
                top_n=top_n,
            )
            success = True
            return result
        finally:
            self._mark_finished(success)

    def _mark_started(self) -> None:
        with self._stats_lock:
            self._queued_requests = max(0, self._queued_requests - 1)
            self._running_requests += 1

    def _mark_finished(self, success: bool) -> None:
        with self._stats_lock:
            self._running_requests = max(0, self._running_requests - 1)
            if success:
                self._completed_requests += 1
            else:
                self._failed_requests += 1
        self._capacity.release()

    def _mark_cancelled_before_start(self) -> None:
        with self._stats_lock:
            self._queued_requests = max(0, self._queued_requests - 1)
        self._capacity.release()

    def _inc_stat(self, attr: str, delta: int) -> None:
        with self._stats_lock:
            setattr(self, attr, max(0, getattr(self, attr) + delta))
