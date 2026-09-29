"""Adapter from public NautilusTrader data objects to project events.

This module is the market-data boundary. It uses the installed
``nautilus_trader`` package and does not modify Nautilus source.
Live clients are not constructed here.
"""

from decimal import Decimal

from nautilus_trader.model.book import OrderBook
from nautilus_trader.model.data import QuoteTick, TradeTick

from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.normalize import normalize_book, normalize_quote, normalize_trade


class NautilusMarketSource:
    """Normalize QuoteTick, TradeTick, and OrderBook from the public model API."""

    def normalize(
        self,
        raw: object,
        *,
        local_receive_timestamp_ns: int,
        normalized_timestamp_ns: int,
    ) -> NormalizedMarketEvent:
        if isinstance(raw, QuoteTick):
            return _quote(raw, local_receive_timestamp_ns, normalized_timestamp_ns)
        if isinstance(raw, TradeTick):
            return _trade(raw, local_receive_timestamp_ns, normalized_timestamp_ns)
        if isinstance(raw, OrderBook):
            return _book(raw, local_receive_timestamp_ns, normalized_timestamp_ns)
        raise TypeError("NautilusMarketSource accepts QuoteTick, TradeTick, or OrderBook")


def _quote(
    tick: QuoteTick,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
) -> NormalizedMarketEvent:
    symbol, venue = _instrument_parts(tick.instrument_id)
    return normalize_quote(
        symbol=symbol,
        venue=venue,
        bid_price=str(tick.bid_price),
        ask_price=str(tick.ask_price),
        bid_size=str(tick.bid_size),
        ask_size=str(tick.ask_size),
        exchange_timestamp_ns=int(tick.ts_event),
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(tick.ts_init),
    )


def _trade(
    tick: TradeTick,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
) -> NormalizedMarketEvent:
    symbol, venue = _instrument_parts(tick.instrument_id)
    side_name = str(getattr(tick.aggressor_side, "name", "UNKNOWN"))
    side = {
        "BUYER": TradeSide.BUY.value,
        "SELLER": TradeSide.SELL.value,
    }.get(side_name, TradeSide.UNKNOWN.value)
    return normalize_trade(
        symbol=symbol,
        venue=venue,
        price=str(tick.price),
        size=str(tick.size),
        side=side,
        trade_id=str(tick.trade_id),
        exchange_timestamp_ns=int(tick.ts_event),
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(tick.ts_init),
    )


def _book(
    book: OrderBook,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
) -> NormalizedMarketEvent:
    symbol, venue = _instrument_parts(book.instrument_id)
    exchange_ts = int(book.ts_event)
    if exchange_ts == 0:
        exchange_ts = int(book.ts_last)
    book_type = getattr(book.book_type, "name", None) or str(book.book_type)
    return normalize_book(
        symbol=symbol,
        venue=venue,
        bids=_levels(book.bids()),
        asks=_levels(book.asks()),
        book_type=str(book_type),
        sequence=int(book.sequence),
        exchange_timestamp_ns=exchange_ts,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(book.ts_init),
    )


def _instrument_parts(instrument_id: object) -> tuple[str, str]:
    symbol = instrument_id.symbol.value  # type: ignore[attr-defined]
    venue = instrument_id.venue.value  # type: ignore[attr-defined]
    return str(symbol), str(venue)


def _levels(levels: list[object]) -> tuple[tuple[str, str], ...]:
    rendered: list[tuple[str, str]] = []
    for level in levels:
        size = sum((Decimal(str(order.size)) for order in level.orders()), Decimal("0"))  # type: ignore[attr-defined]
        rendered.append((str(level.price), format(size, "f")))  # type: ignore[attr-defined]
    return tuple(rendered)
