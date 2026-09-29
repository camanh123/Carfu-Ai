"""Hot path: normalize, aggregate, enqueue. No disk I/O and no per-event logs."""

from dataclasses import dataclass, replace

from btc_intelligence.diagnostics.distribution import DistributionSummary, LatencyDistribution
from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.domain.identifiers import ExchangeId
from btc_intelligence.domain.multistate import MultiVenueBTCMarketState
from btc_intelligence.market.multi import MultiExchangeAggregator
from btc_intelligence.market.source import MarketSource
from btc_intelligence.pipeline import Clock, SystemClock
from btc_intelligence.recorder.jsonl import AsyncJsonlRecorder, record_from_event


@dataclass(frozen=True, slots=True)
class StageSummaries:
    receive_to_normalize: DistributionSummary
    normalize_to_aggregate: DistributionSummary
    aggregate_to_recorder: DistributionSummary
    total_internal: DistributionSummary
    observed_timestamp_delta: DistributionSummary


class SensorPipeline:
    def __init__(
        self,
        *,
        source: MarketSource,
        aggregator: MultiExchangeAggregator,
        recorder: AsyncJsonlRecorder,
        session_id: str,
        clock: Clock | None = None,
    ) -> None:
        self._source = source
        self._aggregator = aggregator
        self._recorder = recorder
        self._session_id = session_id
        self._clock = clock or SystemClock()
        self._receive_to_normalize = LatencyDistribution()
        self._normalize_to_aggregate = LatencyDistribution()
        self._aggregate_to_recorder = LatencyDistribution()
        self._total = LatencyDistribution()
        self._observed_delta = LatencyDistribution()
        self._handled = 0
        self._invalid = 0

    @property
    def handled(self) -> int:
        return self._handled

    @property
    def invalid(self) -> int:
        return self._invalid

    @property
    def aggregator(self) -> MultiExchangeAggregator:
        return self._aggregator

    @property
    def recorder(self) -> AsyncJsonlRecorder:
        return self._recorder

    def handle(self, raw: object, *, local_receive_ns: int | None = None) -> MultiVenueBTCMarketState | None:
        received_ns = self._clock.now_ns() if local_receive_ns is None else local_receive_ns
        try:
            draft = self._source.normalize(
                raw,
                local_receive_timestamp_ns=received_ns,
                normalized_timestamp_ns=received_ns,
            )
        except (TypeError, ValueError):
            self._invalid += 1
            return None
        normalized_ns = self._clock.now_ns()
        event = _restamp_normalized(draft, normalized_ns)
        self._aggregator.apply(event, aggregated_ns=normalized_ns)
        aggregated_ns = self._clock.now_ns()
        state = self._aggregator.snapshot(aggregated_ns)
        self._record(event, state, aggregated_ns=aggregated_ns, enqueued_ns=aggregated_ns)
        enqueued_ns = self._clock.now_ns()
        self._measure(event, received_ns, normalized_ns, aggregated_ns, enqueued_ns)
        self._handled += 1
        return state

    def observe_clock(self, now_ns: int) -> MultiVenueBTCMarketState:
        return self._aggregator.observe_clock(now_ns)

    def note_disconnected(self, venue: ExchangeId, *, now_ns: int) -> MultiVenueBTCMarketState:
        return self._aggregator.note_disconnected(venue, now_ns=now_ns)

    def summaries(self) -> StageSummaries:
        return StageSummaries(
            receive_to_normalize=self._receive_to_normalize.summary(),
            normalize_to_aggregate=self._normalize_to_aggregate.summary(),
            aggregate_to_recorder=self._aggregate_to_recorder.summary(),
            total_internal=self._total.summary(),
            observed_timestamp_delta=self._observed_delta.summary(),
        )

    def close(self) -> None:
        self._recorder.close()

    def _record(
        self,
        event: NormalizedMarketEvent,
        state: MultiVenueBTCMarketState,
        *,
        aggregated_ns: int,
        enqueued_ns: int,
    ) -> None:
        view = state.venue(event.instrument.venue)
        health = view.health.value if view is not None else "UNKNOWN"
        self._recorder.enqueue(
            record_from_event(
                event,
                session_id=self._session_id,
                health=health,
                aggregated_ns=aggregated_ns,
                enqueued_ns=enqueued_ns,
            )
        )

    def _measure(
        self,
        event: NormalizedMarketEvent,
        local_receive_ns: int,
        normalized_ns: int,
        aggregated_ns: int,
        enqueued_ns: int,
    ) -> None:
        self._receive_to_normalize.add(normalized_ns - local_receive_ns)
        self._normalize_to_aggregate.add(aggregated_ns - normalized_ns)
        self._aggregate_to_recorder.add(enqueued_ns - aggregated_ns)
        self._total.add(enqueued_ns - local_receive_ns)
        exchange_ns = event.timestamps.exchange_event_ns
        if exchange_ns is None:
            exchange_ns = event.timestamps.exchange_transaction_ns
        if exchange_ns is not None:
            self._observed_delta.add(local_receive_ns - exchange_ns)


def _restamp_normalized(event: NormalizedMarketEvent, normalized_ns: int) -> NormalizedMarketEvent:
    timestamps = event.timestamps.stamp(normalized_timestamp_ns=normalized_ns)
    if event.quote is not None:
        payload = replace(event.quote, timestamps=timestamps)
        return replace(event, timestamps=timestamps, quote=payload)
    if event.trade is not None:
        payload = replace(event.trade, timestamps=timestamps)
        return replace(event, timestamps=timestamps, trade=payload)
    if event.book is not None:
        payload = replace(event.book, timestamps=timestamps)
        return replace(event, timestamps=timestamps, book=payload)
    if event.book_delta is not None:
        payload = replace(event.book_delta, timestamps=timestamps)
        return replace(event, timestamps=timestamps, book_delta=payload)
    return replace(event, timestamps=timestamps)
