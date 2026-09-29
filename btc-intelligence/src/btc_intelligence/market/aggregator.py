"""Fold normalized events into BTCMarketState."""

from dataclasses import replace
from decimal import Decimal

from btc_intelligence.domain.events import MarketEventKind, NormalizedMarketEvent
from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.domain.state import BTCMarketState


class MarketAggregator:
    """Keeps the latest quote, trade, and book view for one instrument."""

    def __init__(self, instrument: InstrumentRef) -> None:
        self._state = BTCMarketState.empty(instrument)

    @property
    def state(self) -> BTCMarketState:
        return self._state

    def apply(self, event: NormalizedMarketEvent, *, aggregated_timestamp_ns: int) -> BTCMarketState:
        if not isinstance(event, NormalizedMarketEvent):
            raise TypeError("aggregator accepts only NormalizedMarketEvent")
        if event.instrument != self._state.instrument:
            raise ValueError(
                f"event instrument {event.instrument} does not match aggregator {self._state.instrument}"
            )
        timestamps = event.timestamps.stamp(aggregated_timestamp_ns=aggregated_timestamp_ns)
        current = self._state
        if event.kind is MarketEventKind.QUOTE:
            quote = event.quote
            assert quote is not None
            mid = (quote.bid_price + quote.ask_price) / Decimal("2")
            spread = quote.ask_price - quote.bid_price
            current = replace(
                current,
                bid_price=quote.bid_price,
                ask_price=quote.ask_price,
                bid_size=quote.bid_size,
                ask_size=quote.ask_size,
                mid_price=mid,
                spread=spread,
                timestamps=timestamps,
                update_count=current.update_count + 1,
            )
        elif event.kind is MarketEventKind.TRADE:
            trade = event.trade
            assert trade is not None
            current = replace(
                current,
                last_trade_price=trade.price,
                last_trade_size=trade.size,
                last_trade_side=trade.side,
                timestamps=timestamps,
                update_count=current.update_count + 1,
            )
        elif event.kind is MarketEventKind.BOOK:
            book = event.book
            assert book is not None
            current = replace(
                current,
                bids=book.bids,
                asks=book.asks,
                timestamps=timestamps,
                update_count=current.update_count + 1,
            )
        else:
            raise ValueError(f"unsupported event kind {event.kind}")
        self._state = current
        return current
