"""Shared constructors for normalized events."""

from decimal import Decimal, InvalidOperation

from btc_intelligence.domain.events import (
    BookDelta,
    BookDeltaEvent,
    BookEvent,
    BookLevel,
    BookSide,
    BookUpdateAction,
    NormalizedMarketEvent,
    QuoteEvent,
    TradeEvent,
)
from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.domain.trades import TradeSide


def parse_decimal(name: str, raw: str) -> Decimal:
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError(f"{name} must be a non-empty decimal string")
    try:
        value = Decimal(raw)
    except InvalidOperation as exc:
        raise ValueError(f"{name} is not a decimal: {raw!r}") from exc
    if not value.is_finite():
        raise ValueError(f"{name} must be finite")
    return value


def build_timestamps(
    *,
    exchange_timestamp_ns: int | None,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
    source_init_timestamp_ns: int | None = None,
    exchange_event_ns: int | None = None,
    exchange_transaction_ns: int | None = None,
) -> EventTimestamps:
    return EventTimestamps(
        exchange_timestamp_ns=exchange_timestamp_ns,
        exchange_event_ns=exchange_event_ns,
        exchange_transaction_ns=exchange_transaction_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=source_init_timestamp_ns,
    )


def normalize_quote(
    *,
    symbol: str,
    venue: str,
    bid_price: str,
    ask_price: str,
    bid_size: str,
    ask_size: str,
    exchange_timestamp_ns: int | None,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
    source_init_timestamp_ns: int | None = None,
    exchange_event_ns: int | None = None,
    exchange_transaction_ns: int | None = None,
) -> NormalizedMarketEvent:
    timestamps = build_timestamps(
        exchange_timestamp_ns=exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=source_init_timestamp_ns,
        exchange_event_ns=exchange_event_ns,
        exchange_transaction_ns=exchange_transaction_ns,
    )
    quote = QuoteEvent(
        instrument=InstrumentRef.parse(symbol, venue),
        bid_price=parse_decimal("bid_price", bid_price),
        ask_price=parse_decimal("ask_price", ask_price),
        bid_size=parse_decimal("bid_size", bid_size),
        ask_size=parse_decimal("ask_size", ask_size),
        timestamps=timestamps,
    )
    return NormalizedMarketEvent.from_quote(quote)


def normalize_trade(
    *,
    symbol: str,
    venue: str,
    price: str,
    size: str,
    side: str,
    trade_id: str,
    exchange_timestamp_ns: int | None,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
    source_init_timestamp_ns: int | None = None,
    exchange_event_ns: int | None = None,
    exchange_transaction_ns: int | None = None,
) -> NormalizedMarketEvent:
    try:
        trade_side = TradeSide(side)
    except ValueError as exc:
        raise ValueError(f"side must be one of {[item.value for item in TradeSide]}") from exc
    timestamps = build_timestamps(
        exchange_timestamp_ns=exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=source_init_timestamp_ns,
        exchange_event_ns=exchange_event_ns,
        exchange_transaction_ns=exchange_transaction_ns,
    )
    trade = TradeEvent(
        instrument=InstrumentRef.parse(symbol, venue),
        price=parse_decimal("price", price),
        size=parse_decimal("size", size),
        side=trade_side,
        trade_id=trade_id,
        timestamps=timestamps,
    )
    return NormalizedMarketEvent.from_trade(trade)


def normalize_book(
    *,
    symbol: str,
    venue: str,
    bids: tuple[tuple[str, str], ...],
    asks: tuple[tuple[str, str], ...],
    book_type: str,
    sequence: int | None,
    exchange_timestamp_ns: int | None,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
    source_init_timestamp_ns: int | None = None,
    exchange_event_ns: int | None = None,
    exchange_transaction_ns: int | None = None,
) -> NormalizedMarketEvent:
    timestamps = build_timestamps(
        exchange_timestamp_ns=exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=source_init_timestamp_ns,
        exchange_event_ns=exchange_event_ns,
        exchange_transaction_ns=exchange_transaction_ns,
    )
    book = BookEvent(
        instrument=InstrumentRef.parse(symbol, venue),
        bids=tuple(BookLevel(parse_decimal("bid_price", price), parse_decimal("bid_size", size)) for price, size in bids),
        asks=tuple(BookLevel(parse_decimal("ask_price", price), parse_decimal("ask_size", size)) for price, size in asks),
        book_type=book_type,
        sequence=sequence,
        timestamps=timestamps,
    )
    return NormalizedMarketEvent.from_book(book)


def normalize_book_deltas(
    *,
    symbol: str,
    venue: str,
    deltas: tuple[BookDelta, ...],
    sequence: int | None,
    is_snapshot: bool,
    exchange_timestamp_ns: int | None,
    local_receive_timestamp_ns: int,
    normalized_timestamp_ns: int,
    source_init_timestamp_ns: int | None = None,
    exchange_event_ns: int | None = None,
    exchange_transaction_ns: int | None = None,
) -> NormalizedMarketEvent:
    timestamps = build_timestamps(
        exchange_timestamp_ns=exchange_timestamp_ns,
        local_receive_timestamp_ns=local_receive_timestamp_ns,
        normalized_timestamp_ns=normalized_timestamp_ns,
        source_init_timestamp_ns=source_init_timestamp_ns,
        exchange_event_ns=exchange_event_ns,
        exchange_transaction_ns=exchange_transaction_ns,
    )
    event = BookDeltaEvent(
        instrument=InstrumentRef.parse(symbol, venue),
        deltas=deltas,
        sequence=sequence,
        is_snapshot=is_snapshot,
        timestamps=timestamps,
    )
    return NormalizedMarketEvent.from_book_delta(event)


def book_delta_from_parts(
    *,
    action: str,
    side: str | None,
    price: str | None,
    size: str | None,
    sequence: int | None,
) -> BookDelta:
    update = BookUpdateAction(action)
    book_side = BookSide(side) if side is not None else None
    return BookDelta(
        action=update,
        side=book_side,
        price=parse_decimal("price", price) if price is not None else None,
        size=parse_decimal("size", size) if size is not None else None,
        sequence=sequence,
    )
