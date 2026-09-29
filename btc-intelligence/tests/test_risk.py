"""Risk rejection contracts."""

from decimal import Decimal

from btc_intelligence.domain.decisions import Decision
from btc_intelligence.risk.guard import RiskGuard
from tests.helpers import intent


def test_wait_without_side_effects_is_allowed() -> None:
    assessment = RiskGuard().assess(intent(Decision.WAIT))
    assert assessment.allowed is True
    assert assessment.reason == "wait_allowed"


def test_buy_is_rejected() -> None:
    assessment = RiskGuard().assess(intent(Decision.BUY, quantity=Decimal("0.01")))
    assert assessment.allowed is False
    assert assessment.reason == "buy_not_authorized"


def test_sell_is_rejected() -> None:
    assessment = RiskGuard().assess(intent(Decision.SELL, quantity=Decimal("0.01")))
    assert assessment.allowed is False
    assert assessment.reason == "sell_not_authorized"


def test_leverage_is_rejected() -> None:
    assessment = RiskGuard().assess(intent(Decision.WAIT, leverage=Decimal("2")))
    assert assessment.allowed is False
    assert assessment.reason == "leverage_disabled"


def test_live_trading_flag_is_rejected() -> None:
    assessment = RiskGuard().assess(intent(Decision.WAIT, live_trading=True))
    assert assessment.allowed is False
    assert assessment.reason == "live_trading_disabled"


def test_withdrawal_is_rejected() -> None:
    assessment = RiskGuard().assess(intent(Decision.WAIT, withdraw=True))
    assert assessment.allowed is False
    assert assessment.reason == "withdrawals_disabled"


def test_quantity_on_wait_is_rejected() -> None:
    assessment = RiskGuard().assess(intent(Decision.WAIT, quantity=Decimal("1")))
    assert assessment.allowed is False
    assert assessment.reason == "quantity_not_allowed_for_wait"
