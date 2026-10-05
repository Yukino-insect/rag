"""Lightweight process-local service governance helpers."""

from __future__ import annotations

import threading
import time


class TokenBucketRateLimiter:
    """A small in-memory token bucket for single-process FastAPI services."""

    def __init__(self, rate_per_second: float, burst: int):
        self.rate_per_second = max(0.0, float(rate_per_second))
        self.burst = max(0, int(burst))
        self._tokens = float(self.burst)
        self._last_refill = time.monotonic()
        self._lock = threading.Lock()

    @property
    def enabled(self) -> bool:
        return self.rate_per_second > 0 and self.burst > 0

    def allow(self, tokens: int = 1) -> bool:
        if not self.enabled:
            return True
        needed = max(1, int(tokens))
        with self._lock:
            now = time.monotonic()
            elapsed = max(0.0, now - self._last_refill)
            self._tokens = min(self.burst, self._tokens + elapsed * self.rate_per_second)
            self._last_refill = now
            if self._tokens < needed:
                return False
            self._tokens -= needed
            return True

    def retry_after_seconds(self, tokens: int = 1) -> int:
        if not self.enabled:
            return 0
        needed = max(1, int(tokens))
        with self._lock:
            missing = max(0.0, needed - self._tokens)
        if missing <= 0:
            return 1
        return max(1, int(missing / self.rate_per_second + 0.999))
