=== BTC-INTELLIGENCE PHASE 0 REPORT ===

Repository structure:

```
btc-intelligence/
    README.md
    PHASE0_REPORT.md
    pyproject.toml
    config/
        phase0.toml
        environment.json
    scripts/
        smoke_nautilus.py
        record_environment.py
    src/btc_intelligence/
        safety.py
        config.py
        pipeline.py
        domain/
        market/
        signals/
        decision/
        risk/
        execution/
        recorder/
        diagnostics/
    tests/
```

The package root is `btc-intelligence/`. It does not import or modify the surrounding Android tree. NautilusTrader is not vendored.

Nautilus version: 1.231.0

Python version: 3.12.3 (CPython, GCC 13.3.0)

Platform: Linux-6.12.94+-x86_64-with-glibc2.39

Nautilus installation PASS

Installation method: `pip install nautilus_trader==1.231.0` from PyPI, binary wheel `nautilus_trader-1.231.0-cp312-cp312-manylinux_2_35_x86_64.whl`.

Version selection: 1.231.0 is the latest stable release on PyPI and matches `requires_python >=3.12,<3.15`. `2.0.0rc5` (uploaded 2026-09-15) was inspected and not pinned. Upstream describes `2.0.0rcN` wheels as community testing builds and does not recommend them for production.

Import smoke test PASS

`scripts/smoke_nautilus.py` and `tests/test_nautilus_smoke.py` import `nautilus_trader` 1.231.0, construct `QuoteTick`, `TradeTick`, and `OrderBook` through the public model API, and normalize them with `NautilusMarketSource`. `Strategy.submit_order` is present and is not called. No trading node is started.

Domain models:

- `NormalizedMarketEvent`
- `QuoteEvent`
- `TradeEvent`
- `BookEvent`
- `BTCMarketState`
- `SignalSnapshot`
- `TradeDecision`
- `TradeIntent`
- `Decision`: `BUY`, `SELL`, `WAIT`

MarketSource abstraction:

`MarketSource` is the only ingestion seam. `NeutralMarketSource` accepts project-owned raw records. `NautilusMarketSource` is the only module that imports `nautilus_trader`, and it translates `QuoteTick`, `TradeTick`, and `OrderBook` into `NormalizedMarketEvent`. Signal, decision, risk, and domain modules do not import Nautilus or an exchange SDK.

ExecutionPort abstraction:

`DecisionEngine` returns `WAIT` and does not accept an execution client. `RiskGuard` sits in front of submission. `DisabledExecutionPort` and `NautilusExecutionPort` both implement `ExecutionPort` and refuse every intent. The pipeline does not call `submit` on the Phase 0 WAIT path. `NautilusExecutionPort` does not import Nautilus order types and does not construct a trading node.

Recorder abstraction:

`Recorder` can store normalized market events, `BTCMarketState`, signals, decisions, trade intents, and execution results. `MemoryRecorder` keeps those records in process. No database is started.

Latency diagnostics:

Every market event can carry `exchange_timestamp_ns`, `local_receive_timestamp_ns`, `normalized_timestamp_ns`, and later processing marks for aggregate, signal, decision, and execution request. Nautilus `ts_init` is preserved separately as `source_init_timestamp_ns` and is not labeled as local receive time. `LatencyDiagnostics` computes:

- receive → normalize
- normalize → aggregate
- aggregate → signal
- signal → decision
- decision → execution request

Durations are nanoseconds. Incomplete stages stay unmeasured. Negative durations are kept. No millisecond performance claim is made.

Tests:

Test count: 49

PASS

Covered: domain models, normalization contracts, market aggregation contracts, decision contracts, risk rejection contracts, execution abstraction, latency and recorder, config and import boundaries, Nautilus import smoke test.

Nautilus modifications:

NONE

Live trading:

DISABLED

Secrets:

NONE

No `.env` file is required. `config/phase0.toml` holds venue names and the disabled safety flags only.

Architecture violations:

NONE

The intelligence path depends on project types. The Nautilus dependency is confined to `market/nautilus_source.py` plus the smoke test and environment script.

Known blockers:

NONE for Phase 0.

Notes, not fork triggers:

- Installed `nautilus_trader` 1.231.0 has adapter packages for Binance, Bybit, OKX, Hyperliquid, and Deribit. It has no `nautilus_trader.adapters.coinbase` module and no `coinbase` string in the installed package. Upstream's project README lists Coinbase as an integration. Phase 0 only stores `COINBASE` as configuration. Public `QuoteTick`, `TradeTick`, and `OrderBook` values are exposed, so there is no demonstrated case of required BTC data being discarded.
- No Nautilus internal latency was measured, and no public-API gap was found for this phase. The fork gate was not met. Nautilus source was not patched.

Recommended Phase 1:

Subscribe to public BTC market data through Nautilus' public data callbacks for one venue, still with live trading, leverage, withdrawals, and order submission disabled. Feed those callbacks through `NautilusMarketSource` into the existing pipeline and append recorder output to files. Collect the latency stages already defined here. Do not add a trading rule. Confirm whether a Coinbase adapter exists in a later pinned Nautilus release before treating `COINBASE` as connectable.
