"""Decision and intent types. Phase 0 emits WAIT only."""

from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps


class Decision(str, Enum):
    BUY = "BUY"
    SELL = "SELL"
    WAIT = "WAIT"


@dataclass(frozen=True, slots=True)
class TradeIntent:
    """A request the risk layer may approve. It is not an exchange order."""

    decision: Decision
    instrument: InstrumentRef
    quantity: Decimal | None
    reason: str
    live_trading: bool
    leverage: Decimal | None
    withdraw: bool
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.decision, Decision):
            raise ValueError("decision must be a Decision")
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        if self.quantity is not None and (isinstance(self.quantity, bool) or not isinstance(self.quantity, Decimal)):
            raise ValueError("quantity must be a Decimal or None")
        if self.quantity is not None and self.quantity <= 0:
            raise ValueError("quantity must be > 0 when set")
        if self.leverage is not None and (isinstance(self.leverage, bool) or not isinstance(self.leverage, Decimal)):
            raise ValueError("leverage must be a Decimal or None")
        if not isinstance(self.live_trading, bool) or not isinstance(self.withdraw, bool):
            raise ValueError("live_trading and withdraw must be bool")
        if not isinstance(self.reason, str) or not self.reason:
            raise ValueError("reason must be a non-empty string")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")


@dataclass(frozen=True, slots=True)
class TradeDecision:
    decision: Decision
    instrument: InstrumentRef
    reason: str
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.decision, Decision):
            raise ValueError("decision must be a Decision")
        if not isinstance(self.instrument, InstrumentRef):
            raise ValueError("instrument must be an InstrumentRef")
        if not isinstance(self.reason, str) or not self.reason:
            raise ValueError("reason must be a non-empty string")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")

    def to_intent(self) -> TradeIntent:
        """Build an intent. WAIT carries no quantity, leverage, or withdrawal."""

        return TradeIntent(
            decision=self.decision,
            instrument=self.instrument,
            quantity=None,
            reason=self.reason,
            live_trading=False,
            leverage=None,
            withdraw=False,
            timestamps=self.timestamps,
        )
