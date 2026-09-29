"""Append-only in-memory recorder."""

from dataclasses import dataclass, field

from btc_intelligence.domain.decisions import TradeDecision, TradeIntent
from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.domain.execution import ExecutionResult
from btc_intelligence.domain.signals import SignalSnapshot
from btc_intelligence.domain.state import BTCMarketState


@dataclass
class MemoryRecorder:
    market_events: list[NormalizedMarketEvent] = field(default_factory=list)
    market_states: list[BTCMarketState] = field(default_factory=list)
    signals: list[SignalSnapshot] = field(default_factory=list)
    decisions: list[TradeDecision] = field(default_factory=list)
    intents: list[TradeIntent] = field(default_factory=list)
    executions: list[ExecutionResult] = field(default_factory=list)

    def record_market_event(self, event: NormalizedMarketEvent) -> None:
        self.market_events.append(event)

    def record_market_state(self, state: BTCMarketState) -> None:
        self.market_states.append(state)

    def record_signal(self, signal: SignalSnapshot) -> None:
        self.signals.append(signal)

    def record_decision(self, decision: TradeDecision) -> None:
        self.decisions.append(decision)

    def record_intent(self, intent: TradeIntent) -> None:
        self.intents.append(intent)

    def record_execution(self, result: ExecutionResult) -> None:
        self.executions.append(result)
