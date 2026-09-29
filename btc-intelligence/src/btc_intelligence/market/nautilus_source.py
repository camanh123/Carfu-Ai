"""Adapter from public NautilusTrader data objects to project events.

This module is the market-data boundary. It uses the installed
``nautilus_trader`` package and does not modify Nautilus source.
Live clients are not constructed here.
"""

from decimal import Decimal

from nautilus_trader.model.book import OrderBook
from nautilus_trader.model.data import OrderBookDeltas, QuoteTick, TradeTick

from btc_intelligence.domain.events import BookDelta, NormalizedMarketEvent
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.nautilus_time import classify_exchange_time
from btc_intelligence.market.normalize import (
    book_delta_from_parts,
    normalize_book,
    normalize_book_deltas,
    normalize_quote,
    normalize_trade,
)


class NautilusMarketSource:
    """Normalize public Nautilus market objects into project events.

    Accepted inputs are ``QuoteTick``, ``TradeTick``, ``OrderBook``, and
    ``OrderBookDeltas``. Adapter classes are not constructed here.
    """

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
        if isinstance(raw, OrderBookDeltas):
            return _deltas(raw, local_receive_timestamp_ns, normalized_timestamp_ns)
        if isinstance(raw, OrderBook):
            return _book(raw, local_receive_timestamp_ns, normalized_timestamp_ns)
        raise TypeError("NautilusMarketSource accepts QuoteTick, TradeTick, OrderBookDeltas, or OrderBook")


def _quote(
    tick: QuoteTick,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
) -> NormalizedMarketEvent:
    symbol, venue = _instrument_parts(tick.instrument_id)
    classified = classify_exchange_time(
        venue=venue,
        kind="QUOTE",
        ts_event_ns=int(tick.ts_event),
        ts_init_ns=int(tick.ts_init),
    )
    return normalize_quote(
        symbol=symbol,
        venue=venue,
        bid_price=str(tick.bid_price),
        ask_price=str(tick.ask_price),
        bid_size=str(tick.bid_size),
        ask_size=str(tick.ask_size),
        exchange_timestamp_ns=classified.exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(tick.ts_init),
        exchange_event_ns=classified.exchange_event_ns,
        exchange_transaction_ns=classified.exchange_transaction_ns,
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
    classified = classify_exchange_time(
        venue=venue,
        kind="TRADE",
        ts_event_ns=int(tick.ts_event),
        ts_init_ns=int(tick.ts_init),
    )
    return normalize_trade(
        symbol=symbol,
        venue=venue,
        price=str(tick.price),
        size=str(tick.size),
        side=side,
        trade_id=str(tick.trade_id),
        exchange_timestamp_ns=classified.exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(tick.ts_init),
        exchange_event_ns=classified.exchange_event_ns,
        exchange_transaction_ns=classified.exchange_transaction_ns,
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
    classified = classify_exchange_time(
        venue=venue,
        kind="BOOK",
        ts_event_ns=exchange_ts,
        ts_init_ns=int(book.ts_init),
    )
    book_type = getattr(book.book_type, "name", None) or str(book.book_type)
    return normalize_book(
        symbol=symbol,
        venue=venue,
        bids=_levels(book.bids()),
        asks=_levels(book.asks()),
        book_type=str(book_type),
        sequence=int(book.sequence),
        exchange_timestamp_ns=classified.exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(book.ts_init),
        exchange_event_ns=classified.exchange_event_ns,
        exchange_transaction_ns=classified.exchange_transaction_ns,
    )


def _deltas(
    deltas: OrderBookDeltas,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
) -> NormalizedMarketEvent:
    symbol, venue = _instrument_parts(deltas.instrument_id)
    classified = classify_exchange_time(
        venue=venue,
        kind="BOOK",
        ts_event_ns=int(deltas.ts_event),
        ts_init_ns=int(deltas.ts_init),
    )
    converted: list[BookDelta] = []
    for delta in deltas.deltas:
        converted.append(_one_delta(delta))
    sequence = int(deltas.sequence)
    return normalize_book_deltas(
        symbol=symbol,
        venue=venue,
        deltas=tuple(converted),
        sequence=sequence,
        is_snapshot=bool(deltas.is_snapshot),
        exchange_timestamp_ns=classified.exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=int(deltas.ts_init),
        exchange_event_ns=classified.exchange_event_ns,
        exchange_transaction_ns=classified.exchange_transaction_ns,
    )


def _one_delta(delta: object) -> BookDelta:
    action_name = str(getattr(delta.action, "name", delta.action))  # type: ignore[attr-defined]
    sequence = int(delta.sequence)  # type: ignore[attr-defined]
    if action_name == "CLEAR":
        return book_delta_from_parts(
            action="CLEAR",
            side=None,
            price=None,
            size=None,
            sequence=sequence,
        )
    order = delta.order  # type: ignore[attr-defined]
    if order is None:
        raise ValueError("book delta is missing its order")
    side_name = str(getattr(order.side, "name", order.side))
    side = {"BUY": "BID", "SELL": "ASK"}.get(side_name)
    if side is None:
        raise ValueError(f"unsupported book side {side_name}")
    return book_delta_from_parts(
        action=action_name,
        side=side,
        price=str(order.price),
        size=str(order.size),
        sequence=sequence,
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
