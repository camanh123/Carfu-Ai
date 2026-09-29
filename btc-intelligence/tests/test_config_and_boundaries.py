"""Configuration, safety constants, and import boundaries."""

import ast
from pathlib import Path

import pytest

from btc_intelligence.config import load_config
from btc_intelligence.domain.identifiers import ExchangeId
from btc_intelligence.safety import LEVERAGE_ENABLED, LIVE_TRADING_ENABLED, WITHDRAWALS_ENABLED

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "btc_intelligence"
CONFIG = ROOT / "config" / "phase0.toml"

ALLOWED_NAUTILUS_IMPORT = SRC / "market" / "nautilus_source.py"


def test_phase0_config_lists_venues_and_disables_trading() -> None:
    config = load_config(CONFIG)
    assert config.live_trading is False
    assert config.leverage_enabled is False
    assert config.withdrawals_enabled is False
    assert config.primary_exchange is ExchangeId.BINANCE
    assert config.instrument.symbol == "BTCUSDT"
    assert config.enabled_exchanges == tuple(ExchangeId)
    assert LIVE_TRADING_ENABLED is False
    assert LEVERAGE_ENABLED is False
    assert WITHDRAWALS_ENABLED is False


def test_config_rejects_live_trading() -> None:
    text = CONFIG.read_text(encoding="utf-8").replace("live_trading = false", "live_trading = true", 1)
    path = ROOT / "config" / "_phase0_live_rejected.toml"
    path.write_text(text, encoding="utf-8")
    try:
        with pytest.raises(ValueError, match="live trading is disabled"):
            load_config(path)
    finally:
        path.unlink(missing_ok=True)


def test_safety_constants_are_literal_false() -> None:
    module = ast.parse((SRC / "safety.py").read_text(encoding="utf-8"))
    values: dict[str, bool] = {}
    for node in module.body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and isinstance(node.value, ast.Constant):
            values[node.target.id] = node.value.value
    assert values["LIVE_TRADING_ENABLED"] is False
    assert values["LEVERAGE_ENABLED"] is False
    assert values["WITHDRAWALS_ENABLED"] is False


def test_intelligence_modules_do_not_import_exchange_sdks() -> None:
    offenders: list[str] = []
    for path in SRC.rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            else:
                continue
            for name in names:
                if name.startswith("nautilus_trader") and path != ALLOWED_NAUTILUS_IMPORT:
                    offenders.append(f"{path}:{name}")
                if any(token in name for token in ("binance", "bybit", "ccxt", "httpx", "requests", "dotenv")):
                    offenders.append(f"{path}:{name}")
    assert offenders == []


def test_decision_engine_does_not_reference_execution() -> None:
    source = (SRC / "decision" / "engine.py").read_text(encoding="utf-8")
    assert "ExecutionPort" not in source
    assert "submit" not in source
    assert "binance" not in source.lower()


def test_repository_has_no_secret_assignments_or_nautilus_vendor_tree() -> None:
    assert not (ROOT / "nautilus_trader").exists()
    banned_lines: list[str] = []
    for path in list(SRC.rglob("*.py")) + list((ROOT / "config").glob("*")) + list((ROOT / "scripts").glob("*.py")):
        if path.suffix not in {".py", ".toml", ".json", ".md"} and path.name != "phase0.toml":
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            lowered = line.lower()
            if "api_key" in lowered or "api_secret" in lowered or "private_key" in lowered:
                banned_lines.append(f"{path}:{lineno}")
    assert banned_lines == []
