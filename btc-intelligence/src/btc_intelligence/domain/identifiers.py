"""Venue-neutral instrument identity."""

from dataclasses import dataclass
from enum import Enum


class ExchangeId(str, Enum):
    """Configured venues. Presence here is not a live connection."""

    BINANCE = "BINANCE"
    BYBIT = "BYBIT"
    OKX = "OKX"
    COINBASE = "COINBASE"
    HYPERLIQUID = "HYPERLIQUID"
    DERIBIT = "DERIBIT"


@dataclass(frozen=True, slots=True)
class InstrumentRef:
    """Project-owned instrument key. Not a Nautilus InstrumentId."""

    symbol: str
    venue: ExchangeId

    def __post_init__(self) -> None:
        if not isinstance(self.symbol, str) or not self.symbol.strip():
            raise ValueError("symbol must be a non-empty string")
        if self.symbol != self.symbol.strip() or any(ch.isspace() for ch in self.symbol):
            raise ValueError("symbol must not contain whitespace")
        if not isinstance(self.venue, ExchangeId):
            raise ValueError("venue must be an ExchangeId")

    @classmethod
    def parse(cls, symbol: str, venue: str) -> "InstrumentRef":
        try:
            exchange = ExchangeId(venue)
        except ValueError as exc:
            known = ", ".join(item.value for item in ExchangeId)
            raise ValueError(f"unknown venue {venue!r}; expected one of {known}") from exc
        return cls(symbol=symbol, venue=exchange)

    def __str__(self) -> str:
        return f"{self.symbol}.{self.venue.value}"
