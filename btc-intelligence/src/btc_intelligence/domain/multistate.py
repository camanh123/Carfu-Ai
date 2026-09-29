"""Multi-venue BTC state. The reference price is descriptive only."""

from dataclasses import dataclass
from decimal import Decimal

from btc_intelligence.domain.events import BookLevel
from btc_intelligence.domain.health import VenueHealth
from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.domain.trades import TradeSide


@dataclass(frozen=True, slots=True)
class VenueMarketView:
    venue: ExchangeId
    instrument: InstrumentRef
    health: VenueHealth
    book_trusted: bool
    best_bid: Decimal | None
    best_ask: Decimal | None
    bid_size: Decimal | None
    ask_size: Decimal | None
    spread: Decimal | None
    mid_price: Decimal | None
    last_trade_price: Decimal | None
    last_trade_size: Decimal | None
    last_trade_side: TradeSide | None
    bids: tuple[BookLevel, ...]
    asks: tuple[BookLevel, ...]
    freshness_ns: int | None
    timestamps: EventTimestamps
    contributes: bool


@dataclass(frozen=True, slots=True)
class AggregateMarketView:
    """Cross-venue summary of healthy books.

    ``reference_price`` is the median of healthy mid prices. It is not a
    fair value, a prediction, or an expected price.
    ``cross_exchange_deviation`` is ``max_mid - min_mid`` when at least two
    healthy mids exist.
    """

    healthy_exchange_count: int
    reference_price: Decimal | None
    min_mid: Decimal | None
    max_mid: Decimal | None
    cross_exchange_deviation: Decimal | None
    freshest_exchange: ExchangeId | None
    timestamp_ns: int | None


@dataclass(frozen=True, slots=True)
class MultiVenueBTCMarketState:
    venues: tuple[VenueMarketView, ...]
    aggregate: AggregateMarketView

    def venue(self, exchange: ExchangeId) -> VenueMarketView | None:
        for view in self.venues:
            if view.venue is exchange:
                return view
        return None
