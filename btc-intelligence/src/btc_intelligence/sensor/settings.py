"""Phase 1 sensor settings. Credentials are not part of this file."""

from dataclasses import dataclass
from pathlib import Path
import tomllib

from btc_intelligence.domain.identifiers import ExchangeId
from btc_intelligence.safety import LEVERAGE_ENABLED, LIVE_TRADING_ENABLED, WITHDRAWALS_ENABLED


@dataclass(frozen=True, slots=True)
class VenueFeed:
    venue: ExchangeId
    symbol: str
    book_depth: int
    http_base: str | None = None
    ws_base: str | None = None


@dataclass(frozen=True, slots=True)
class Phase1Config:
    live_trading: bool
    leverage_enabled: bool
    withdrawals_enabled: bool
    publish_depth: int
    stale_after_ns: int
    disconnect_after_ns: int
    recorder_queue: int
    feeds: tuple[VenueFeed, ...]

    def __post_init__(self) -> None:
        if LIVE_TRADING_ENABLED or self.live_trading:
            raise ValueError("live trading is disabled")
        if LEVERAGE_ENABLED or self.leverage_enabled:
            raise ValueError("leverage is disabled")
        if WITHDRAWALS_ENABLED or self.withdrawals_enabled:
            raise ValueError("withdrawals are disabled")
        if self.publish_depth <= 0:
            raise ValueError("publish_depth must be > 0")
        if self.stale_after_ns <= 0 or self.disconnect_after_ns <= self.stale_after_ns:
            raise ValueError("disconnect_after_ns must be greater than stale_after_ns")
        if self.recorder_queue <= 0:
            raise ValueError("recorder_queue must be > 0")
        if not self.feeds:
            raise ValueError("at least one venue feed is required")

    def select(self, names: tuple[str, ...]) -> tuple[VenueFeed, ...]:
        wanted = tuple(ExchangeId(name.strip().upper()) for name in names if name.strip())
        if not wanted:
            raise ValueError("select at least one venue")
        by_venue = {feed.venue: feed for feed in self.feeds}
        missing = [venue.value for venue in wanted if venue not in by_venue]
        if missing:
            raise ValueError(f"unconfigured venues: {', '.join(missing)}")
        return tuple(by_venue[venue] for venue in wanted)


def load_phase1(path: Path) -> Phase1Config:
    payload = tomllib.loads(path.read_text(encoding="utf-8"))
    sensor = payload["sensor"]
    feeds: list[VenueFeed] = []
    for name, section in payload["venues"].items():
        if not section.get("enabled", False):
            continue
        feeds.append(
            VenueFeed(
                venue=ExchangeId(name.upper()),
                symbol=str(section["symbol"]),
                book_depth=int(section["book_depth"]),
                http_base=section.get("http_base"),
                ws_base=section.get("ws_base"),
            )
        )
    return Phase1Config(
        live_trading=bool(payload["live_trading"]),
        leverage_enabled=bool(payload["leverage_enabled"]),
        withdrawals_enabled=bool(payload["withdrawals_enabled"]),
        publish_depth=int(sensor["publish_depth"]),
        stale_after_ns=int(sensor["stale_after_ms"]) * 1_000_000,
        disconnect_after_ns=int(sensor["disconnect_after_ms"]) * 1_000_000,
        recorder_queue=int(sensor["recorder_queue"]),
        feeds=tuple(feeds),
    )


def default_phase1_path() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "config" / "phase1.toml"
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("config/phase1.toml was not found from the package location")
