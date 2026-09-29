"""Streaming latency summaries. Percentiles are measurements, not targets."""

from dataclasses import dataclass
import math
import random


@dataclass(frozen=True, slots=True)
class DistributionSummary:
    count: int
    mean_ns: float | None
    p50_ns: int | None
    p95_ns: int | None
    p99_ns: int | None
    p99_9_ns: int | None
    max_ns: int | None


class LatencyDistribution:
    """Exact samples up to ``exact_limit``, then a reservoir of that size.

    ``count``, ``mean``, and ``max`` stay exact after the reservoir starts.
    Percentiles then describe the reservoir.
    """

    def __init__(self, *, exact_limit: int = 200_000, seed: int = 1) -> None:
        if exact_limit <= 0:
            raise ValueError("exact_limit must be > 0")
        self._limit = exact_limit
        self._samples: list[int] = []
        self._count = 0
        self._total = 0
        self._max: int | None = None
        self._random = random.Random(seed)

    def add(self, duration_ns: int) -> None:
        if isinstance(duration_ns, bool) or not isinstance(duration_ns, int):
            raise ValueError("duration_ns must be an int")
        self._count += 1
        self._total += duration_ns
        if self._max is None or duration_ns > self._max:
            self._max = duration_ns
        if len(self._samples) < self._limit:
            self._samples.append(duration_ns)
            return
        slot = self._random.randrange(self._count)
        if slot < self._limit:
            self._samples[slot] = duration_ns

    def summary(self) -> DistributionSummary:
        if self._count == 0:
            return DistributionSummary(0, None, None, None, None, None, None)
        ordered = sorted(self._samples)
        return DistributionSummary(
            count=self._count,
            mean_ns=self._total / self._count,
            p50_ns=_percentile(ordered, 50),
            p95_ns=_percentile(ordered, 95),
            p99_ns=_percentile(ordered, 99),
            p99_9_ns=_percentile(ordered, 99.9) if self._count >= 1000 else None,
            max_ns=self._max,
        )


def _percentile(ordered: list[int], percent: float) -> int:
    if len(ordered) == 1:
        return ordered[0]
    rank = (len(ordered) - 1) * (percent / 100)
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return ordered[low]
    weight = rank - low
    return int(round(ordered[low] * (1 - weight) + ordered[high] * weight))
