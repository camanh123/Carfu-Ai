"""Normalization contracts for the venue-neutral source."""

from decimal import Decimal

import pytest

from btc_intelligence.domain.events import MarketEventKind
from btc_intelligence.domain.identifiers import ExchangeId
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.source import NeutralMarketSource
from tests.helpers import book_record, quote_record, trade_record


def test_quote_contract_keeps_exact_decimals_and_timestamps() -> None:
    event = NeutralMarketSource().normalize(
        quote_record(),
        local_receive_timestamp_ns=11,
        normalized_timestamp_ns=22,
    )
    assert event.kind is MarketEventKind.QUOTE
    assert event.quote is not None
    assert event.instrument.venue is ExchangeId.BINANCE
    assert event.quote.bid_price == Decimal("64000.10")
    assert event.timestamps.local_receive_timestamp_ns == 11
    assert event.timestamps.normalized_timestamp_ns == 22
    assert event.timestamps.aggregated_timestamp_ns is None


def test_same_contract_accepts_each_configured_venue() -> None:
    source = NeutralMarketSource()
    for venue in ("BINANCE", "BYBIT", "OKX", "COINBASE", "HYPERLIQUID", "DERIBIT"):
        event = source.normalize(
            quote_record(venue=venue),
            local_receive_timestamp_ns=1,
            normalized_timestamp_ns=2,
        )
        assert event.instrument.venue.value == venue


def test_trade_contract_maps_side() -> None:
    event = NeutralMarketSource().normalize(
        trade_record(side="SELL"),
        local_receive_timestamp_ns=1,
        normalized_timestamp_ns=2,
    )
    assert event.kind is MarketEventKind.TRADE
    assert event.trade is not None
    assert event.trade.side is TradeSide.SELL
    assert event.trade.price == Decimal("64000.20")
    assert event.trade.trade_id == "trade-1"


def test_book_contract_preserves_level_order() -> None:
    event = NeutralMarketSource().normalize(
        book_record(),
        local_receive_timestamp_ns=3,
        normalized_timestamp_ns=4,
    )
    assert event.kind is MarketEventKind.BOOK
    assert event.book is not None
    assert event.instrument.venue is ExchangeId.BYBIT
    assert [level.price for level in event.book.bids] == [Decimal("64000.00"), Decimal("63999.50")]
    assert event.book.asks[0].size == Decimal("3")
    assert event.book.sequence == 7
    assert event.book.book_type == "L2_MBP"


def test_negative_size_is_rejected() -> None:
    with pytest.raises(ValueError, match="bid_size"):
        NeutralMarketSource().normalize(
            quote_record(bid_size="-0.1"),
            local_receive_timestamp_ns=1,
            normalized_timestamp_ns=2,
        )


def test_non_decimal_price_is_rejected() -> None:
    with pytest.raises(ValueError, match="bid_price"):
        NeutralMarketSource().normalize(
            quote_record(bid="not-a-price"),
            local_receive_timestamp_ns=1,
            normalized_timestamp_ns=2,
        )


def test_foreign_object_is_rejected() -> None:
    with pytest.raises(TypeError, match="RawQuoteRecord"):
        NeutralMarketSource().normalize(
            {"bidPrice": "1", "askPrice": "2"},
            local_receive_timestamp_ns=1,
            normalized_timestamp_ns=2,
        )
