"""Venue-neutral raw records accepted by NeutralMarketSource.

These are BTC-Intelligence input records. They are not Binance, Bybit, or
Nautilus objects.
"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RawQuoteRecord:
    symbol: str
    venue: str
    bid_price: str
    ask_price: str
    bid_size: str
    ask_size: str
    exchange_timestamp_ns: int


@dataclass(frozen=True, slots=True)
class RawTradeRecord:
    symbol: str
    venue: str
    price: str
    size: str
    side: str
    trade_id: str
    exchange_timestamp_ns: int


@dataclass(frozen=True, slots=True)
class RawLevel:
    price: str
    size: str


@dataclass(frozen=True, slots=True)
class RawBookRecord:
    symbol: str
    venue: str
    bids: tuple[RawLevel, ...]
    asks: tuple[RawLevel, ...]
    book_type: str
    sequence: int | None
    exchange_timestamp_ns: int
