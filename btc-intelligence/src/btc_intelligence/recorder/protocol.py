"""Recorder surface for a later persistence backend."""

from typing import Protocol, runtime_checkable

from btc_intelligence.domain.decisions import TradeDecision, TradeIntent
from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.domain.execution import ExecutionResult
from btc_intelligence.domain.signals import SignalSnapshot
from btc_intelligence.domain.state import BTCMarketState


@runtime_checkable
class Recorder(Protocol):
    def record_market_event(self, event: NormalizedMarketEvent) -> None:
        """Store one normalized market event."""

    def record_market_state(self, state: BTCMarketState) -> None:
        """Store one aggregated market state."""

    def record_signal(self, signal: SignalSnapshot) -> None:
        """Store one signal snapshot."""

    def record_decision(self, decision: TradeDecision) -> None:
        """Store one trade decision."""

    def record_intent(self, intent: TradeIntent) -> None:
        """Store one trade intent."""

    def record_execution(self, result: ExecutionResult) -> None:
        """Store one execution result."""
