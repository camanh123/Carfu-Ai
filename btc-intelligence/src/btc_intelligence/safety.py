"""Phase 0 hard safety switches.

These values are source constants. They are not read from the environment,
and live trading cannot be enabled by configuration.
"""

LIVE_TRADING_ENABLED: bool = False
LEVERAGE_ENABLED: bool = False
WITHDRAWALS_ENABLED: bool = False

PHASE0_DECISION_POLICY = "wait_only_no_strategy"
