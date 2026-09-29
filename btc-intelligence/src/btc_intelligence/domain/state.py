"""Aggregated BTC market state owned by the intelligence layer."""

from dataclasses import dataclass
from decimal import Decimal

from btc_intelligence.domain.events import BookLevel
from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.domain.trades import TradeSide


@dataclass(frozen=True, slots=True)
class BTCMarketState:
    instrument: InstrumentRef
    bid_price: Decimal | None
    ask_price: Decimal | None
    bid_size: Decimal | None
    ask_size: Decimal | None
    mid_price: Decimal | None
    spread: Decimal | None
    last_trade_price: Decimal | None
    last_trade_size: Decimal | None
    last_trade_side: TradeSide | None
    bids: tuple[BookLevel, ...]
    asks: tuple[BookLevel, ...]
    timestamps: EventTimestamps
    update_count: int

    def __post_init__(self) -> None:
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        if isinstance(self.update_count, bool) or not isinstance(self.update_count, int) or self.update_count < 0:
            raise ValueError("update_count must be an int >= 0")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")

    @classmethod
    def empty(cls, instrument: InstrumentRef) -> "BTCMarketState":
        return cls(
            instrument=instrument,
            bid_price=None,
            ask_price=None,
            bid_size=None,
            ask_size=None,
            mid_price=None,
            spread=None,
            last_trade_price=None,
            last_trade_size=None,
            last_trade_side=None,
            bids=(),
            asks=(),
            timestamps=EventTimestamps(),
            update_count=0,
        )
