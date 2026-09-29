"""Future Nautilus execution seam.

Phase 0 does not construct a Nautilus trading node, does not import an
exchange adapter, and does not call ``Strategy.submit_order``. The public
order API remains available in the installed package for a later phase.
Until live trading is an explicit, reviewed change, this port refuses every
intent before any Nautilus order type is built.
"""

from btc_intelligence.domain.decisions import TradeIntent
from btc_intelligence.domain.execution import ExecutionResult, ExecutionStatus
from btc_intelligence.safety import LIVE_TRADING_ENABLED


class NautilusExecutionPort:
    def submit(
        self,
        intent: TradeIntent,
        *,
        execution_request_timestamp_ns: int,
    ) -> ExecutionResult:
        if not isinstance(intent, TradeIntent):
            raise TypeError("execution port accepts only TradeIntent")
        if LIVE_TRADING_ENABLED:
            raise RuntimeError("safety invariant broken: live trading must stay disabled")
        return ExecutionResult(
            status=ExecutionStatus.REJECTED,
            reason="nautilus_execution_disabled",
            submitted_to_venue=False,
            timestamps=intent.timestamps.stamp(
                execution_request_timestamp_ns=execution_request_timestamp_ns
            ),
        )
