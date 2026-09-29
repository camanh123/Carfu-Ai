"""Fold normalized events from many venues into one BTC market state."""

from dataclasses import dataclass, field
from decimal import Decimal

from btc_intelligence.domain.events import MarketEventKind, NormalizedMarketEvent
from btc_intelligence.domain.health import VenueHealth
from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.domain.multistate import AggregateMarketView, MultiVenueBTCMarketState, VenueMarketView
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.book import L2Book
from btc_intelligence.market.reference import freshest_venue, median_price


@dataclass
class VenueQuality:
    events: int = 0
    quotes: int = 0
    trades: int = 0
    book_updates: int = 0
    invalid_events: int = 0
    out_of_order: int = 0
    duplicates: int = 0
    desync_count: int = 0
    reconnect_count: int = 0
    stale_periods: int = 0
    quote_book_disagreements: int = 0
    substituted_exchange_clock: int = 0
    last_event_ns: int | None = None
    last_quote_ns: int | None = None
    last_trade_ns: int | None = None
    last_book_ns: int | None = None
    first_event_ns: int | None = None


@dataclass
class _VenueRuntime:
    instrument: InstrumentRef
    book: L2Book
    health: VenueHealth = VenueHealth.STARTING
    quality: VenueQuality = field(default_factory=VenueQuality)
    last_trade_price: Decimal | None = None
    last_trade_size: Decimal | None = None
    last_trade_side: TradeSide | None = None
    quote_bid: Decimal | None = None
    quote_ask: Decimal | None = None
    quote_bid_size: Decimal | None = None
    quote_ask_size: Decimal | None = None
    timestamps: EventTimestamps = field(default_factory=EventTimestamps)
    was_disconnected: bool = False


class MultiExchangeAggregator:
    """One book and health machine per venue. Unhealthy venues are excluded."""

    def __init__(
        self,
        instruments: dict[ExchangeId, InstrumentRef],
        *,
        publish_depth: int = 50,
        stale_after_ns: int = 5_000_000_000,
        disconnect_after_ns: int = 30_000_000_000,
    ) -> None:
        if stale_after_ns <= 0 or disconnect_after_ns <= stale_after_ns:
            raise ValueError("disconnect_after_ns must be greater than stale_after_ns")
        self._stale_after_ns = stale_after_ns
        self._disconnect_after_ns = disconnect_after_ns
        self._venues = {
            venue: _VenueRuntime(instrument=instrument, book=L2Book(publish_depth=publish_depth))
            for venue, instrument in instruments.items()
        }

    @property
    def venues(self) -> tuple[ExchangeId, ...]:
        return tuple(self._venues)

    def quality(self, venue: ExchangeId) -> VenueQuality:
        return self._venues[venue].quality

    def health(self, venue: ExchangeId) -> VenueHealth:
        return self._venues[venue].health

    def apply(self, event: NormalizedMarketEvent, *, aggregated_ns: int) -> MultiVenueBTCMarketState:
        runtime = self._runtime_for(event.instrument)
        runtime.timestamps = event.timestamps.stamp(aggregated_timestamp_ns=aggregated_ns)
        self._touch(runtime, aggregated_ns)
        if event.kind is MarketEventKind.QUOTE:
            self._apply_quote(runtime, event)
        elif event.kind is MarketEventKind.TRADE:
            self._apply_trade(runtime, event)
        elif event.kind is MarketEventKind.BOOK_DELTA:
            self._apply_deltas(runtime, event)
        elif event.kind is MarketEventKind.BOOK:
            runtime.quality.book_updates += 1
        else:
            raise ValueError(f"unsupported event kind {event.kind}")
        self._refresh_health(runtime, aggregated_ns)
        return self.snapshot(aggregated_ns)

    def note_invalid(self, venue: ExchangeId, *, now_ns: int) -> MultiVenueBTCMarketState:
        runtime = self._venues[venue]
        runtime.quality.invalid_events += 1
        self._enter(runtime, VenueHealth.DESYNCED, now_ns)
        runtime.book.trusted = False
        return self.snapshot(now_ns)

    def note_disconnected(self, venue: ExchangeId, *, now_ns: int) -> MultiVenueBTCMarketState:
        runtime = self._venues[venue]
        self._enter(runtime, VenueHealth.DISCONNECTED, now_ns)
        runtime.was_disconnected = True
        runtime.book.trusted = False
        return self.snapshot(now_ns)

    def observe_clock(self, now_ns: int) -> MultiVenueBTCMarketState:
        for runtime in self._venues.values():
            self._refresh_health(runtime, now_ns)
        return self.snapshot(now_ns)

    def snapshot(self, now_ns: int) -> MultiVenueBTCMarketState:
        views = tuple(self._view(runtime, now_ns) for runtime in self._venues.values())
        healthy = [view for view in views if view.contributes and view.mid_price is not None]
        mids = [view.mid_price for view in healthy if view.mid_price is not None]
        reference = median_price(mids)
        minimum = min(mids) if mids else None
        maximum = max(mids) if mids else None
        deviation = maximum - minimum if minimum is not None and maximum is not None and len(mids) >= 2 else None
        freshest = freshest_venue(
            [
                (view.venue, view.timestamps.aggregated_timestamp_ns)
                for view in healthy
                if view.timestamps.aggregated_timestamp_ns is not None
            ]
        )
        aggregate = AggregateMarketView(
            healthy_exchange_count=len(healthy),
            reference_price=reference,
            min_mid=minimum,
            max_mid=maximum,
            cross_exchange_deviation=deviation,
            freshest_exchange=freshest,
            timestamp_ns=now_ns,
        )
        return MultiVenueBTCMarketState(venues=views, aggregate=aggregate)

    def _apply_quote(self, runtime: _VenueRuntime, event: NormalizedMarketEvent) -> None:
        quote = event.quote
        assert quote is not None
        runtime.quality.quotes += 1
        runtime.quality.last_quote_ns = event.timestamps.local_receive_timestamp_ns
        runtime.quote_bid = quote.bid_price
        runtime.quote_ask = quote.ask_price
        runtime.quote_bid_size = quote.bid_size
        runtime.quote_ask_size = quote.ask_size
        if event.timestamps.exchange_timestamp_ns is None and event.timestamps.exchange_event_ns is None:
            runtime.quality.substituted_exchange_clock += 1
        bid = runtime.book.best_bid()
        ask = runtime.book.best_ask()
        if runtime.book.trusted and bid is not None and ask is not None:
            if bid[0] != quote.bid_price or ask[0] != quote.ask_price:
                runtime.quality.quote_book_disagreements += 1

    def _apply_trade(self, runtime: _VenueRuntime, event: NormalizedMarketEvent) -> None:
        trade = event.trade
        assert trade is not None
        runtime.quality.trades += 1
        runtime.quality.last_trade_ns = event.timestamps.local_receive_timestamp_ns
        runtime.last_trade_price = trade.price
        runtime.last_trade_size = trade.size
        runtime.last_trade_side = trade.side

    def _apply_deltas(self, runtime: _VenueRuntime, event: NormalizedMarketEvent) -> None:
        book_event = event.book_delta
        assert book_event is not None
        runtime.quality.book_updates += 1
        runtime.quality.last_book_ns = event.timestamps.local_receive_timestamp_ns
        if book_event.is_snapshot and runtime.health in {
            VenueHealth.DESYNCED,
            VenueHealth.DISCONNECTED,
            VenueHealth.STALE,
        }:
            self._enter(runtime, VenueHealth.RECOVERING, event.timestamps.aggregated_timestamp_ns or 0)
        result = runtime.book.apply(book_event)
        if result.duplicate:
            runtime.quality.duplicates += 1
        moment = event.timestamps.aggregated_timestamp_ns or 0
        if result.out_of_order:
            runtime.quality.out_of_order += 1
            self._enter(runtime, VenueHealth.DESYNCED, moment)
        elif result.invalid:
            runtime.quality.invalid_events += 1
            self._enter(runtime, VenueHealth.DESYNCED, moment)
        elif result.crossed:
            self._enter(runtime, VenueHealth.DESYNCED, moment)
        elif result.trusted and runtime.health in {VenueHealth.STARTING, VenueHealth.RECOVERING, VenueHealth.STALE}:
            self._enter(runtime, VenueHealth.HEALTHY, moment)

    def _refresh_health(self, runtime: _VenueRuntime, now_ns: int) -> None:
        last = runtime.quality.last_event_ns
        if last is None or runtime.health in {VenueHealth.DESYNCED, VenueHealth.STARTING}:
            return
        age = now_ns - last
        if age >= self._disconnect_after_ns:
            self._enter(runtime, VenueHealth.DISCONNECTED, now_ns)
            runtime.was_disconnected = True
            runtime.book.trusted = False
            return
        if age >= self._stale_after_ns and runtime.health is VenueHealth.HEALTHY:
            self._enter(runtime, VenueHealth.STALE, now_ns)
            return
        if runtime.health is VenueHealth.STALE and age < self._stale_after_ns and runtime.book.trusted:
            self._enter(runtime, VenueHealth.HEALTHY, now_ns)
        if runtime.health is VenueHealth.DISCONNECTED and age < self._stale_after_ns:
            self._enter(runtime, VenueHealth.RECOVERING, now_ns)

    def _enter(self, runtime: _VenueRuntime, health: VenueHealth, now_ns: int) -> None:
        previous = runtime.health
        if previous is health:
            return
        if health is VenueHealth.DESYNCED:
            runtime.quality.desync_count += 1
        if health is VenueHealth.STALE:
            runtime.quality.stale_periods += 1
        if health is VenueHealth.RECOVERING and runtime.was_disconnected:
            runtime.quality.reconnect_count += 1
            runtime.was_disconnected = False
        if health is VenueHealth.HEALTHY and previous is VenueHealth.DISCONNECTED:
            runtime.quality.reconnect_count += 1
            runtime.was_disconnected = False
        runtime.health = health
        if now_ns and runtime.quality.last_event_ns is None:
            runtime.quality.last_event_ns = now_ns

    def _touch(self, runtime: _VenueRuntime, now_ns: int) -> None:
        runtime.quality.events += 1
        runtime.quality.last_event_ns = now_ns
        if runtime.quality.first_event_ns is None:
            runtime.quality.first_event_ns = now_ns
        if runtime.health is VenueHealth.DISCONNECTED:
            self._enter(runtime, VenueHealth.RECOVERING, now_ns)

    def _view(self, runtime: _VenueRuntime, now_ns: int) -> VenueMarketView:
        trusted = runtime.book.trusted and runtime.health is VenueHealth.HEALTHY
        bid = runtime.book.best_bid() if trusted else None
        ask = runtime.book.best_ask() if trusted else None
        best_bid = bid[0] if bid else None
        best_ask = ask[0] if ask else None
        bid_size = bid[1] if bid else None
        ask_size = ask[1] if ask else None
        spread = best_ask - best_bid if best_bid is not None and best_ask is not None else None
        mid = (best_bid + best_ask) / Decimal("2") if best_bid is not None and best_ask is not None else None
        last = runtime.quality.last_event_ns
        freshness = now_ns - last if last is not None else None
        return VenueMarketView(
            venue=runtime.instrument.venue,
            instrument=runtime.instrument,
            health=runtime.health,
            book_trusted=trusted,
            best_bid=best_bid,
            best_ask=best_ask,
            bid_size=bid_size,
            ask_size=ask_size,
            spread=spread,
            mid_price=mid,
            last_trade_price=runtime.last_trade_price,
            last_trade_size=runtime.last_trade_size,
            last_trade_side=runtime.last_trade_side,
            bids=runtime.book.top_bids() if trusted else (),
            asks=runtime.book.top_asks() if trusted else (),
            freshness_ns=freshness,
            timestamps=runtime.timestamps,
            contributes=trusted,
        )

    def _runtime_for(self, instrument: InstrumentRef) -> _VenueRuntime:
        runtime = self._venues.get(instrument.venue)
        if runtime is None or runtime.instrument != instrument:
            raise ValueError(f"no aggregator slot for {instrument}")
        return runtime
