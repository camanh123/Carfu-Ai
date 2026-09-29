"""Reject anything other than an unlevered WAIT with no venue side effects."""

from dataclasses import dataclass

from btc_intelligence.domain.decisions import Decision, TradeIntent
from btc_intelligence.safety import LEVERAGE_ENABLED, LIVE_TRADING_ENABLED, WITHDRAWALS_ENABLED


@dataclass(frozen=True, slots=True)
class RiskAssessment:
    allowed: bool
    reason: str
    intent: TradeIntent


class RiskGuard:
    """Phase 0 policy.

    BUY and SELL are rejected because no strategy is authorized to trade.
    Leverage, withdrawals, and live trading are rejected unconditionally.
    """

    def assess(self, intent: TradeIntent) -> RiskAssessment:
        if not isinstance(intent, TradeIntent):
            raise TypeError("risk guard accepts only TradeIntent")
        if LIVE_TRADING_ENABLED or intent.live_trading:
            return self._reject(intent, "live_trading_disabled")
        if WITHDRAWALS_ENABLED or intent.withdraw:
            return self._reject(intent, "withdrawals_disabled")
        if LEVERAGE_ENABLED or intent.leverage is not None:
            return self._reject(intent, "leverage_disabled")
        if intent.decision is Decision.BUY:
            return self._reject(intent, "buy_not_authorized")
        if intent.decision is Decision.SELL:
            return self._reject(intent, "sell_not_authorized")
        if intent.decision is not Decision.WAIT:
            return self._reject(intent, "decision_not_authorized")
        if intent.quantity is not None:
            return self._reject(intent, "quantity_not_allowed_for_wait")
        return RiskAssessment(allowed=True, reason="wait_allowed", intent=intent)

    @staticmethod
    def _reject(intent: TradeIntent, reason: str) -> RiskAssessment:
        return RiskAssessment(allowed=False, reason=reason, intent=intent)
