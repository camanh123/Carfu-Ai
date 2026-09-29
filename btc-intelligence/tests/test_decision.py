"""Decision contracts. Phase 0 has no trading rule."""

from decimal import Decimal

import pytest

from btc_intelligence.decision.engine import DecisionEngine
from btc_intelligence.domain.decisions import Decision
from btc_intelligence.domain.signals import SignalSnapshot
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.signals.engine import SignalEngine
from btc_intelligence.market.aggregator import MarketAggregator
from tests.helpers import BTC, normalize_quote


def test_signal_engine_does_not_emit_a_direction() -> None:
    state = MarketAggregator(BTC).apply(normalize_quote(), aggregated_timestamp_ns=30)
    signal = SignalEngine().evaluate(state, signal_timestamp_ns=40)
    assert signal.has_directional_signal is False
    assert signal.reason == "no_strategy_configured"
    assert signal.mid_price == Decimal("64000.30")
    assert signal.timestamps.signal_timestamp_ns == 40


def test_decision_engine_emits_wait_only() -> None:
    state = MarketAggregator(BTC).apply(normalize_quote(), aggregated_timestamp_ns=30)
    signal = SignalEngine().evaluate(state, signal_timestamp_ns=40)
    decision = DecisionEngine().decide(signal, decision_timestamp_ns=50)
    assert decision.decision is Decision.WAIT
    assert decision.reason == "no_strategy_configured"
    assert decision.to_intent().decision is Decision.WAIT
    assert decision.timestamps.decision_timestamp_ns == 50


def test_decision_engine_refuses_a_directional_snapshot() -> None:
    signal = SignalSnapshot(
        instrument=BTC,
        has_directional_signal=True,
        reason="forged",
        mid_price=Decimal("1"),
        spread=Decimal("1"),
        last_trade_price=None,
        timestamps=EventTimestamps(),
    )
    with pytest.raises(ValueError, match="directional"):
        DecisionEngine().decide(signal, decision_timestamp_ns=1)
