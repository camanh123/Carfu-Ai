"""Domain model contracts."""

from decimal import Decimal

import pytest

from btc_intelligence.domain.decisions import Decision, TradeDecision
from btc_intelligence.domain.events import BookEvent, BookLevel, MarketEventKind, NormalizedMarketEvent, QuoteEvent
from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.domain.signals import SignalSnapshot
from btc_intelligence.domain.state import BTCMarketState
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.domain.trades import TradeSide
from tests.helpers import BTC, intent, normalize_quote


def test_decision_enum_is_buy_sell_wait() -> None:
    assert {item.value for item in Decision} == {"BUY", "SELL", "WAIT"}


def test_configured_exchanges() -> None:
    assert [item.value for item in ExchangeId] == [
        "BINANCE",
        "BYBIT",
        "OKX",
        "COINBASE",
        "HYPERLIQUID",
        "DERIBIT",
    ]


def test_instrument_rejects_unknown_venue_and_blank_symbol() -> None:
    with pytest.raises(ValueError, match="unknown venue"):
        InstrumentRef.parse("BTCUSDT", "KRAKEN")
    with pytest.raises(ValueError, match="symbol"):
        InstrumentRef(symbol="  ", venue=ExchangeId.BINANCE)


def test_quote_preserves_decimal_and_builds_envelope() -> None:
    event = normalize_quote()
    assert event.kind is MarketEventKind.QUOTE
    assert event.quote is not None
    assert event.quote.bid_price == Decimal("64000.10")
    assert event.quote.ask_price == Decimal("64000.50")
    assert event.quote.bid_size == Decimal("1.25")
    assert event.timestamps.exchange_timestamp_ns == 1_700_000_000_000_000_000
    assert event.timestamps.local_receive_timestamp_ns == 10
    assert event.timestamps.normalized_timestamp_ns == 20


def test_quote_rejects_negative_size() -> None:
    with pytest.raises(ValueError, match="bid_size"):
        QuoteEvent(
            instrument=BTC,
            bid_price=Decimal("1"),
            ask_price=Decimal("2"),
            bid_size=Decimal("-1"),
            ask_size=Decimal("1"),
            timestamps=EventTimestamps(),
        )


def test_normalized_event_rejects_mismatched_payload() -> None:
    quote = normalize_quote().quote
    assert quote is not None
    with pytest.raises(ValueError, match="more than one payload"):
        NormalizedMarketEvent(
            kind=MarketEventKind.QUOTE,
            instrument=quote.instrument,
            timestamps=quote.timestamps,
            quote=quote,
            trade=None,
            book=BookEvent(
                instrument=quote.instrument,
                bids=(),
                asks=(),
                book_type="L2_MBP",
                sequence=0,
                timestamps=quote.timestamps,
            ),
        )


def test_book_level_and_market_state_types() -> None:
    level = BookLevel(price=Decimal("10"), size=Decimal("0"))
    state = BTCMarketState.empty(BTC)
    assert level.size == Decimal("0")
    assert state.update_count == 0
    assert state.last_trade_side is None


def test_signal_snapshot_requires_reason() -> None:
    with pytest.raises(ValueError, match="reason"):
        SignalSnapshot(
            instrument=BTC,
            has_directional_signal=False,
            reason="",
            mid_price=None,
            spread=None,
            last_trade_price=None,
            timestamps=EventTimestamps(),
        )


def test_wait_decision_intent_has_no_order_fields() -> None:
    decision = TradeDecision(
        decision=Decision.WAIT,
        instrument=BTC,
        reason="no_strategy_configured",
        timestamps=EventTimestamps(decision_timestamp_ns=5),
    )
    produced = decision.to_intent()
    assert produced.decision is Decision.WAIT
    assert produced.quantity is None
    assert produced.leverage is None
    assert produced.live_trading is False
    assert produced.withdraw is False


def test_trade_side_values() -> None:
    assert TradeSide.BUY.value == "BUY"
    assert TradeSide.SELL.value == "SELL"


def test_intent_rejects_non_positive_quantity() -> None:
    with pytest.raises(ValueError, match="quantity"):
        intent(Decision.WAIT, quantity=Decimal("0"))
