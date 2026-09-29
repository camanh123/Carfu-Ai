"""Turn a signal snapshot into a trade decision.

Phase 0 has no strategy, so the only decision this engine can produce is WAIT.
It does not call an exchange or an execution port.
"""

from btc_intelligence.domain.decisions import Decision, TradeDecision
from btc_intelligence.domain.signals import SignalSnapshot


class DecisionEngine:
    def decide(self, signal: SignalSnapshot, *, decision_timestamp_ns: int) -> TradeDecision:
        if not isinstance(signal, SignalSnapshot):
            raise TypeError("decision engine accepts only SignalSnapshot")
        if signal.has_directional_signal:
            raise ValueError("Phase 0 signal engine must not set a directional signal")
        return TradeDecision(
            decision=Decision.WAIT,
            instrument=signal.instrument,
            reason="no_strategy_configured",
            timestamps=signal.timestamps.stamp(decision_timestamp_ns=decision_timestamp_ns),
        )
