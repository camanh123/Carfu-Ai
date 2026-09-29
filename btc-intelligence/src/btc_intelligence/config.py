"""Load Phase 0 venue configuration. Secrets are not part of this file."""

from dataclasses import dataclass
from pathlib import Path
import tomllib

from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.safety import LEVERAGE_ENABLED, LIVE_TRADING_ENABLED, WITHDRAWALS_ENABLED


@dataclass(frozen=True, slots=True)
class Phase0Config:
    live_trading: bool
    leverage_enabled: bool
    withdrawals_enabled: bool
    primary_exchange: ExchangeId
    instrument: InstrumentRef
    enabled_exchanges: tuple[ExchangeId, ...]

    def __post_init__(self) -> None:
        if LIVE_TRADING_ENABLED or self.live_trading:
            raise ValueError("live trading is disabled")
        if LEVERAGE_ENABLED or self.leverage_enabled:
            raise ValueError("leverage is disabled")
        if WITHDRAWALS_ENABLED or self.withdrawals_enabled:
            raise ValueError("withdrawals are disabled")
        if self.primary_exchange not in self.enabled_exchanges:
            raise ValueError("primary exchange must be enabled")


def load_config(path: Path) -> Phase0Config:
    payload = tomllib.loads(path.read_text(encoding="utf-8"))
    enabled = tuple(ExchangeId(name) for name in payload["exchanges"]["enabled"])
    primary = ExchangeId(payload["primary_exchange"])
    instrument = InstrumentRef.parse(payload["instrument_symbol"], primary.value)
    return Phase0Config(
        live_trading=bool(payload["live_trading"]),
        leverage_enabled=bool(payload["leverage_enabled"]),
        withdrawals_enabled=bool(payload["withdrawals_enabled"]),
        primary_exchange=primary,
        instrument=instrument,
        enabled_exchanges=enabled,
    )


def default_config_path() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "config" / "phase0.toml"
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("config/phase0.toml was not found from the package location")
