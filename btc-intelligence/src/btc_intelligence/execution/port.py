"""ExecutionPort is the only submission seam in BTC-Intelligence."""

from typing import Protocol, runtime_checkable

from btc_intelligence.domain.decisions import TradeIntent
from btc_intelligence.domain.execution import ExecutionResult


@runtime_checkable
class ExecutionPort(Protocol):
    def submit(
        self,
        intent: TradeIntent,
        *,
        execution_request_timestamp_ns: int,
    ) -> ExecutionResult:
        """Accept an intent. Phase 0 implementations must not reach a venue."""
