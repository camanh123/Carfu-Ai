# BTC-Intelligence

Phase 0 is the frozen architecture bootstrap. Phase 1 adds a public multi-venue market sensor. It observes. It does not predict and it does not trade.

NautilusTrader supplies market-data types and, later, execution infrastructure. BTC-Intelligence owns normalization, aggregation, signals, decisions, risk, and the execution port. NautilusTrader is a pinned dependency. Its source is not forked, copied, or modified.

```
Exchange market data
        |
Nautilus / MarketSource
        |
NormalizedMarketEvent
        |
MarketAggregator
        |
BTCMarketState
        |
SignalEngine
        |
DecisionEngine
        |
BUY / SELL / WAIT TradeIntent
        |
RiskGuard
        |
ExecutionPort
        |
Nautilus execution
        |
Binance
```

Phase 0 stops at the interfaces. `DecisionEngine` always returns `WAIT`. `RiskGuard` rejects `BUY`, `SELL`, leverage, withdrawals, and live trading. `ExecutionPort` implementations refuse venue submission. No strategy, indicator, model, or exchange credential is included.

## Layout

```
src/btc_intelligence/
    market/
    signals/
    decision/
    risk/
    execution/
    recorder/
    diagnostics/
tests/
config/
scripts/
```

## Pinned infrastructure

| Item | Value |
| --- | --- |
| NautilusTrader | `1.231.0` (latest stable on PyPI; not `2.0.0rc5`) |
| Python | `>=3.12,<3.15` |
| Install | `pip install nautilus_trader==1.231.0` (PyPI wheel) |

Public types used by the adapter: `QuoteTick`, `TradeTick`, `OrderBook`, plus `Strategy.submit_order` existence in the import smoke test. The smoke test does not start a trading node.

`config/phase0.toml` names `BINANCE`, `BYBIT`, `OKX`, `COINBASE`, `HYPERLIQUID`, and `DERIBIT`. That list is configuration only.

## Phase 1 sensor

Public Binance and Bybit BTC data enter through a Nautilus data-only node. `MarketSensorActor` subscribes to quotes, trades, and L2 deltas. `NautilusMarketSource` converts those objects into one `NormalizedMarketEvent` model. `MultiExchangeAggregator` keeps a validated L2 book and a health state per venue. Only `HEALTHY` books contribute to the descriptive reference price, which is the median of healthy mid prices. `AsyncJsonlRecorder` appends schema `btc-intelligence.sensor.v1` from a bounded queue. The market callback does not write to disk.

```bash
python scripts/run_sensor.py --venues BINANCE --seconds 1800 --output records
python scripts/run_sensor.py --venues BINANCE,BYBIT --seconds 1800 --output records
```

The adapter audit is `docs/PHASE1_NAUTILUS_AUDIT.md`. Live connectivity tests are marked `integration` and are excluded from the default pytest run.

## Safety

Live trading, leverage, and withdrawals are hard-disabled in `btc_intelligence.safety`. The config loader rejects the opposite settings. No API keys are read. No `.env` file is required.

## Tests

From this directory, with Python 3.12:

```bash
python -m venv .venv
.venv/bin/pip install -e ".[dev]"
.venv/bin/pytest
.venv/bin/python scripts/smoke_nautilus.py
```
