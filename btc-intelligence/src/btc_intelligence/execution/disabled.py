"""Execution port that records a refusal and contacts no venue."""

from btc_intelligence.domain.decisions import TradeIntent
from btc_intelligence.domain.execution import ExecutionResult, ExecutionStatus


class DisabledExecutionPort:
    """Default Phase 0 port. ``submit`` never leaves the process."""

    def submit(
        self,
        intent: TradeIntent,
        *,
        execution_request_timestamp_ns: int,
    ) -> ExecutionResult:
        if not isinstance(intent, TradeIntent):
            raise TypeError("execution port accepts only TradeIntent")
        return ExecutionResult(
            status=ExecutionStatus.REJECTED,
            reason="live_trading_disabled",
            submitted_to_venue=False,
            timestamps=intent.timestamps.stamp(
                execution_request_timestamp_ns=execution_request_timestamp_ns
            ),
        )
