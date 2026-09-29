"""Signal snapshot. Phase 0 carries observations, not a trading rule."""

from dataclasses import dataclass
from decimal import Decimal

from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps


@dataclass(frozen=True, slots=True)
class SignalSnapshot:
    instrument: InstrumentRef
    has_directional_signal: bool
    reason: str
    mid_price: Decimal | None
    spread: Decimal | None
    last_trade_price: Decimal | None
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        if not isinstance(self.has_directional_signal, bool):
            raise ValueError("has_directional_signal must be bool")
        if not isinstance(self.reason, str) or not self.reason:
            raise ValueError("reason must be a non-empty string")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")
