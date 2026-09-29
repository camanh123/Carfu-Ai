"""Market source abstraction.

The intelligence pipeline depends on this protocol. Concrete sources translate
their own inputs into NormalizedMarketEvent before anything else sees them.
"""

from typing import Protocol, runtime_checkable

from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.market.normalize import normalize_book, normalize_quote, normalize_trade
from btc_intelligence.market.records import RawBookRecord, RawQuoteRecord, RawTradeRecord


@runtime_checkable
class MarketSource(Protocol):
    def normalize(
        self,
        raw: object,
        *,
        local_receive_timestamp_ns: int,
        normalized_timestamp_ns: int,
    ) -> NormalizedMarketEvent:
        """Translate one raw input into a project-owned event."""


class NeutralMarketSource:
    """Normalizes BTC-Intelligence raw records. No exchange SDK is imported."""

    def normalize(
        self,
        raw: object,
        *,
        local_receive_timestamp_ns: int,
        normalized_timestamp_ns: int,
    ) -> NormalizedMarketEvent:
        if isinstance(raw, RawQuoteRecord):
            return normalize_quote(
                symbol=raw.symbol,
                venue=raw.venue,
                bid_price=raw.bid_price,
                ask_price=raw.ask_price,
                bid_size=raw.bid_size,
                ask_size=raw.ask_size,
                exchange_timestamp_ns=raw.exchange_timestamp_ns,
                local_receive_timestamp_ns=local_receive_timestamp_ns,
                normalized_timestamp_ns=normalized_timestamp_ns,
            )
        if isinstance(raw, RawTradeRecord):
            return normalize_trade(
                symbol=raw.symbol,
                venue=raw.venue,
                price=raw.price,
                size=raw.size,
                side=raw.side,
                trade_id=raw.trade_id,
                exchange_timestamp_ns=raw.exchange_timestamp_ns,
                local_receive_timestamp_ns=local_receive_timestamp_ns,
                normalized_timestamp_ns=normalized_timestamp_ns,
            )
        if isinstance(raw, RawBookRecord):
            return normalize_book(
                symbol=raw.symbol,
                venue=raw.venue,
                bids=tuple((level.price, level.size) for level in raw.bids),
                asks=tuple((level.price, level.size) for level in raw.asks),
                book_type=raw.book_type,
                sequence=raw.sequence,
                exchange_timestamp_ns=raw.exchange_timestamp_ns,
                local_receive_timestamp_ns=local_receive_timestamp_ns,
                normalized_timestamp_ns=normalized_timestamp_ns,
            )
        raise TypeError(
            "NeutralMarketSource accepts only RawQuoteRecord, RawTradeRecord, or RawBookRecord"
        )
