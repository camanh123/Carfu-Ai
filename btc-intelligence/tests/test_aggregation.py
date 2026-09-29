"""Market aggregation contracts."""

from decimal import Decimal

import pytest

from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.aggregator import MarketAggregator
from btc_intelligence.market.source import NeutralMarketSource
from tests.helpers import BTC, book_record, quote_record, trade_record


def _event_from(raw: object, normalized_ns: int):
    return NeutralMarketSource().normalize(
        raw,
        local_receive_timestamp_ns=normalized_ns - 1,
        normalized_timestamp_ns=normalized_ns,
    )


def test_quote_sets_top_of_book_and_spread() -> None:
    aggregator = MarketAggregator(BTC)
    state = aggregator.apply(_event_from(quote_record(), 20), aggregated_timestamp_ns=30)
    assert state.bid_price == Decimal("64000.10")
    assert state.ask_price == Decimal("64000.50")
    assert state.mid_price == Decimal("64000.30")
    assert state.spread == Decimal("0.40")
    assert state.update_count == 1
    assert state.timestamps.aggregated_timestamp_ns == 30
    assert state.timestamps.normalized_timestamp_ns == 20


def test_later_quote_replaces_top_without_clearing_last_trade() -> None:
    aggregator = MarketAggregator(BTC)
    aggregator.apply(_event_from(quote_record(), 20), aggregated_timestamp_ns=30)
    aggregator.apply(_event_from(trade_record(), 40), aggregated_timestamp_ns=50)
    state = aggregator.apply(
        _event_from(quote_record(bid="100", ask="110", bid_size="1", ask_size="1"), 60),
        aggregated_timestamp_ns=70,
    )
    assert state.bid_price == Decimal("100")
    assert state.mid_price == Decimal("105")
    assert state.last_trade_price == Decimal("64000.20")
    assert state.last_trade_side is TradeSide.BUY
    assert state.update_count == 3


def test_book_replaces_depth_and_keeps_quote() -> None:
    aggregator = MarketAggregator(InstrumentRef(symbol="BTCUSDT", venue=ExchangeId.BYBIT))
    bybit_quote = quote_record(venue="BYBIT", bid="10", ask="12", bid_size="1", ask_size="1")
    aggregator.apply(_event_from(bybit_quote, 2), aggregated_timestamp_ns=3)
    state = aggregator.apply(_event_from(book_record(), 4), aggregated_timestamp_ns=5)
    assert state.bid_price == Decimal("10")
    assert len(state.bids) == 2
    assert state.bids[0].price == Decimal("64000.00")
    assert state.asks[0].size == Decimal("3")


def test_aggregator_rejects_other_instrument() -> None:
    aggregator = MarketAggregator(BTC)
    with pytest.raises(ValueError, match="does not match"):
        aggregator.apply(_event_from(book_record(), 4), aggregated_timestamp_ns=5)


def test_aggregator_rejects_non_normalized_input() -> None:
    aggregator = MarketAggregator(BTC)
    with pytest.raises(TypeError, match="NormalizedMarketEvent"):
        aggregator.apply(quote_record(), aggregated_timestamp_ns=1)  # type: ignore[arg-type]
