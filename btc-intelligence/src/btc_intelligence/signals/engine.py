"""Build a SignalSnapshot from market state without a trade rule."""

from btc_intelligence.domain.signals import SignalSnapshot
from btc_intelligence.domain.state import BTCMarketState


class SignalEngine:
    """Copies observable prices forward. It does not emit a directional signal."""

    def evaluate(self, state: BTCMarketState, *, signal_timestamp_ns: int) -> SignalSnapshot:
        if not isinstance(state, BTCMarketState):
            raise TypeError("signal engine accepts only BTCMarketState")
        return SignalSnapshot(
            instrument=state.instrument,
            has_directional_signal=False,
            reason="no_strategy_configured",
            mid_price=state.mid_price,
            spread=state.spread,
            last_trade_price=state.last_trade_price,
            timestamps=state.timestamps.stamp(signal_timestamp_ns=signal_timestamp_ns),
        )
