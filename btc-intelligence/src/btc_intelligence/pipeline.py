"""Wire the Phase 0 path from a raw record to a recorded WAIT result.

DecisionEngine never receives an execution client. Venue submission can only
happen inside an ExecutionPort, and the pipeline does not call ``submit``
for a risk-rejected intent or for WAIT.
"""

from dataclasses import dataclass

from btc_intelligence.decision.engine import DecisionEngine
from btc_intelligence.diagnostics.latency import LatencyDiagnostics
from btc_intelligence.domain.decisions import TradeDecision, TradeIntent
from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.domain.execution import ExecutionResult, ExecutionStatus
from btc_intelligence.domain.signals import SignalSnapshot
from btc_intelligence.domain.state import BTCMarketState
from btc_intelligence.execution.port import ExecutionPort
from btc_intelligence.market.aggregator import MarketAggregator
from btc_intelligence.market.source import MarketSource
from btc_intelligence.recorder.protocol import Recorder
from btc_intelligence.risk.guard import RiskAssessment, RiskGuard
from btc_intelligence.signals.engine import SignalEngine


class Clock:
    def now_ns(self) -> int:
        raise NotImplementedError


class SystemClock(Clock):
    def now_ns(self) -> int:
        import time

        return time.time_ns()


class SequenceClock(Clock):
    """Deterministic clock for tests and measurement wiring."""

    def __init__(self, start_ns: int, step_ns: int = 1_000) -> None:
        if start_ns < 0 or step_ns <= 0:
            raise ValueError("clock start must be >= 0 and step must be > 0")
        self._now = start_ns
        self._step = step_ns

    def now_ns(self) -> int:
        current = self._now
        self._now += self._step
        return current


@dataclass(frozen=True, slots=True)
class PipelineResult:
    event: NormalizedMarketEvent
    state: BTCMarketState
    signal: SignalSnapshot
    decision: TradeDecision
    intent: TradeIntent
    risk: RiskAssessment
    execution: ExecutionResult
    latency: LatencyDiagnostics
    execution_port_called: bool


class IntelligencePipeline:
    def __init__(
        self,
        *,
        source: MarketSource,
        aggregator: MarketAggregator,
        signals: SignalEngine,
        decisions: DecisionEngine,
        risk: RiskGuard,
        execution: ExecutionPort,
        recorder: Recorder,
        clock: Clock,
    ) -> None:
        self._source = source
        self._aggregator = aggregator
        self._signals = signals
        self._decisions = decisions
        self._risk = risk
        self._execution = execution
        self._recorder = recorder
        self._clock = clock

    @property
    def execution_port(self) -> ExecutionPort:
        """The configured port. Phase 0 processing does not call it."""

        return self._execution

    def process(self, raw: object) -> PipelineResult:
        receive_ns = self._clock.now_ns()
        normalized_ns = self._clock.now_ns()
        event = self._source.normalize(
            raw,
            local_receive_timestamp_ns=receive_ns,
            normalized_timestamp_ns=normalized_ns,
        )
        self._recorder.record_market_event(event)

        aggregated_ns = self._clock.now_ns()
        state = self._aggregator.apply(event, aggregated_timestamp_ns=aggregated_ns)
        self._recorder.record_market_state(state)

        signal_ns = self._clock.now_ns()
        signal = self._signals.evaluate(state, signal_timestamp_ns=signal_ns)
        self._recorder.record_signal(signal)

        decision_ns = self._clock.now_ns()
        decision = self._decisions.decide(signal, decision_timestamp_ns=decision_ns)
        self._recorder.record_decision(decision)

        intent = decision.to_intent()
        self._recorder.record_intent(intent)

        risk = self._risk.assess(intent)
        execution_request_ns = self._clock.now_ns()
        port_called = False
        if risk.allowed:
            execution = ExecutionResult(
                status=ExecutionStatus.NOT_SENT,
                reason="wait_no_order",
                submitted_to_venue=False,
                timestamps=intent.timestamps.stamp(
                    execution_request_timestamp_ns=execution_request_ns
                ),
            )
        else:
            execution = ExecutionResult(
                status=ExecutionStatus.REJECTED,
                reason=risk.reason,
                submitted_to_venue=False,
                timestamps=intent.timestamps.stamp(
                    execution_request_timestamp_ns=execution_request_ns
                ),
            )
        self._recorder.record_execution(execution)
        return PipelineResult(
            event=event,
            state=state,
            signal=signal,
            decision=decision,
            intent=intent,
            risk=risk,
            execution=execution,
            latency=LatencyDiagnostics.from_timestamps(execution.timestamps),
            execution_port_called=port_called,
        )
