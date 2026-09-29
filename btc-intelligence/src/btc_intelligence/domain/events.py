"""Normalized market events. Payloads are venue-neutral."""

from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.domain.trades import TradeSide


class MarketEventKind(str, Enum):
    QUOTE = "QUOTE"
    TRADE = "TRADE"
    BOOK = "BOOK"


def _require_decimal(name: str, value: Decimal) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, Decimal):
        raise ValueError(f"{name} must be a Decimal")
    return value


def _require_positive(name: str, value: Decimal) -> Decimal:
    value = _require_decimal(name, value)
    if value <= 0:
        raise ValueError(f"{name} must be > 0")
    return value


def _require_non_negative(name: str, value: Decimal) -> Decimal:
    value = _require_decimal(name, value)
    if value < 0:
        raise ValueError(f"{name} must be >= 0")
    return value


@dataclass(frozen=True, slots=True)
class BookLevel:
    price: Decimal
    size: Decimal

    def __post_init__(self) -> None:
        _require_positive("price", self.price)
        _require_non_negative("size", self.size)


@dataclass(frozen=True, slots=True)
class QuoteEvent:
    instrument: InstrumentRef
    bid_price: Decimal
    ask_price: Decimal
    bid_size: Decimal
    ask_size: Decimal
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        _require_positive("bid_price", self.bid_price)
        _require_positive("ask_price", self.ask_price)
        _require_non_negative("bid_size", self.bid_size)
        _require_non_negative("ask_size", self.ask_size)
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")


@dataclass(frozen=True, slots=True)
class TradeEvent:
    instrument: InstrumentRef
    price: Decimal
    size: Decimal
    side: TradeSide
    trade_id: str
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        _require_positive("price", self.price)
        _require_positive("size", self.size)
        if not isinstance(self.side, TradeSide):
            raise ValueError("side must be a TradeSide")
        if not isinstance(self.trade_id, str) or not self.trade_id:
            raise ValueError("trade_id must be a non-empty string")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")


@dataclass(frozen=True, slots=True)
class BookEvent:
    instrument: InstrumentRef
    bids: tuple[BookLevel, ...]
    asks: tuple[BookLevel, ...]
    book_type: str
    sequence: int | None
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        if not isinstance(self.bids, tuple) or not isinstance(self.asks, tuple):
            raise ValueError("bids and asks must be tuples")
        if any(not isinstance(level, BookLevel) for level in (*self.bids, *self.asks)):
            raise ValueError("book levels must be BookLevel values")
        if not isinstance(self.book_type, str) or not self.book_type:
            raise ValueError("book_type must be a non-empty string")
        if self.sequence is not None and (isinstance(self.sequence, bool) or not isinstance(self.sequence, int)):
            raise ValueError("sequence must be an int or None")
        if self.sequence is not None and self.sequence < 0:
            raise ValueError("sequence must be >= 0")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")


@dataclass(frozen=True, slots=True)
class NormalizedMarketEvent:
    """Envelope passed from a MarketSource into the aggregator.

    Exactly one of ``quote``, ``trade``, or ``book`` is set, and it matches
    ``kind``. Exchange-specific objects are not valid members of this type.
    """

    kind: MarketEventKind
    instrument: InstrumentRef
    timestamps: EventTimestamps
    quote: QuoteEvent | None = None
    trade: TradeEvent | None = None
    book: BookEvent | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.kind, MarketEventKind):
            raise ValueError("kind must be a MarketEventKind")
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")
        payloads = {
            MarketEventKind.QUOTE: self.quote,
            MarketEventKind.TRADE: self.trade,
            MarketEventKind.BOOK: self.book,
        }
        selected = payloads[self.kind]
        if selected is None:
            raise ValueError(f"{self.kind.value} event requires its payload")
        extras = [name for name, value in payloads.items() if name is not self.kind and value is not None]
        if extras:
            raise ValueError("normalized event carries more than one payload")
        if not isinstance(selected, (QuoteEvent, TradeEvent, BookEvent)):
            raise ValueError("payload must be a project market event")
        if selected.instrument != self.instrument:
            raise ValueError("payload instrument does not match the envelope")
        if selected.timestamps != self.timestamps:
            raise ValueError("payload timestamps do not match the envelope")

    @classmethod
    def from_quote(cls, quote: QuoteEvent) -> "NormalizedMarketEvent":
        return cls(
            kind=MarketEventKind.QUOTE,
            instrument=quote.instrument,
            timestamps=quote.timestamps,
            quote=quote,
        )

    @classmethod
    def from_trade(cls, trade: TradeEvent) -> "NormalizedMarketEvent":
        return cls(
            kind=MarketEventKind.TRADE,
            instrument=trade.instrument,
            timestamps=trade.timestamps,
            trade=trade,
        )

    @classmethod
    def from_book(cls, book: BookEvent) -> "NormalizedMarketEvent":
        return cls(
            kind=MarketEventKind.BOOK,
            instrument=book.instrument,
            timestamps=book.timestamps,
            book=book,
        )
