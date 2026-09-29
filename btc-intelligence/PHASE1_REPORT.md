# === BTC-INTELLIGENCE PHASE 1 REPORT ===

## BASELINE

Phase 0 preserved: yes. The single-venue aggregator, `DecisionEngine` WAIT path, risk guard, and execution refusal are unchanged. The sensor does not call them.

Nautilus version: 1.231.0

Python version: 3.12.3 CPython

Nautilus modifications: ZERO

## NAUTILUS API AUDIT

Binance adapter: `BinanceDataClientConfig` public spot client. `api_key=None` and `api_secret=None` select public data. Instrument `BTCUSDT.BINANCE`. Quotes use `bookTicker`. Trades use spot `@trade` (`use_agg_trade_ticks=False`). L2 uses diff depth at 100 ms plus an HTTP snapshot when depth is 1000. This environment gets HTTP 451 from `api.binance.com`. The live client was pointed at `https://data-api.binance.vision` and `wss://data-stream.binance.vision`.

Bybit adapter: `BybitDataClientConfig` with product type `SPOT` and public websockets from `BybitWebSocketClient.new_public`. Instrument `BTCUSDT-SPOT.BYBIT`. Spot quotes are `orderbook.1`. Trades are the public trade stream. L2 depth is 50. Instrument loading uses Bybit REST. This environment receives CloudFront HTTP 403 on that REST call, so the Nautilus client never subscribes. A separate raw handshake to `wss://stream.bybit.com/v5/public/spot` returned HTTP 101 and a pong. That handshake is not the sensor.

Quote support: `QuoteTick` on both adapters.

Trade support: `TradeTick` with aggressor `BUYER` or `SELLER`.

L2 support: `OrderBookDeltas` for `BookType.L2_MBP`. `L3_MBO` is not published by Binance. Bybit skips any book type other than `L2_MBP`.

Timestamp semantics: documented in `docs/PHASE1_NAUTILUS_AUDIT.md`. Exchange event time, trade time, local receive time, and Nautilus `ts_init` are stored separately. When Binance copies `ts_init` into `ts_event`, the exchange fields are left empty.

Important limitations: Binance discards trade event time `E` and diff first update id `U`. Bybit discards order-book matching time `cts` and the trade envelope timestamp. Unavailable fields stay empty.

## PHASE 1A — BINANCE

Instrument: BTCUSDT.BINANCE spot

Connection: public data client via the Binance vision HTTP and websocket mirrors. No API key.

Quotes: bookTicker. 201,159 quotes. Every quote had `ts_event == ts_init`, so the exchange clock was not stored (`substituted_exchange_clock` = 201,159).

Trades: raw `@trade`. 54,036 trades. Aggressor kept. `exchange_transaction_ns` is trade time `T`. Event time `E` is not on the Nautilus object.

L2 book: diff depth 1000 plus HTTP snapshot. 17,992 book updates. Sequence moved forward with 0 duplicates and 0 backwards updates. End-of-run book was trusted. Mid at the last snapshot was 84402.175.

Session: `20260929T124105Z-5a30b815`

Runtime: 1,800 seconds requested. Wall clock in the summary is 1,810.8 seconds, including connect and shutdown. A status line every 30 seconds from 30 seconds through 1,772 seconds reported `health=HEALTHY`.

Total events: 273,187

Events/sec: 150.86 over that wall clock

Reconnects: 0

Invalid events: 0

Out-of-order: 0

Stale periods: 0 during the run. `freshness_ns` in the summary is about 10.3 seconds because the summary is written after the node stops.

Book desyncs: 0

Internal latency, nanoseconds, 273,187 samples. p99.9 is reported because the sample count is above 1,000.

receive→normalize p50/p95/p99/max: 22912 / 100426 / 232542 / 8634090

normalize→aggregate p50/p95/p99/max: 599173 / 851339 / 1130881 / 3454175

aggregate→recorder p50/p95/p99/max: 522296 / 712069 / 1064149 / 2543229

total p50/p95/p99/max: 1162324 / 1621755 / 1940852 / 11459425

p99.9 total: 2,677,824 ns

Recorder:

records: 273,187

drops: 0

Observed timestamp delta, for events that still had an exchange time (54,036 trades plus book updates that carried event time `E`; quotes excluded): count 72,027, p50 161,141,604 ns, p95 615,287,647 ns, p99 998,146,394 ns, max 1,871,901,966 ns. This is not network latency.

Quote versus book top disagreed on 79,342 quotes. The streams run at different rates. Disagreement does not mark the book desynced. The aggregate uses the validated book.

## PHASE 1B — BYBIT

Instrument: BTCUSDT-SPOT.BYBIT

Connection: FAIL inside Nautilus. `BybitInstrumentProvider` received HTTP 403: "The Amazon CloudFront distribution is configured to block access from your country." The node then sat in `RUNNING` with the data engine disconnected. Process exit code was 0 because the timer stopped a node that had already logged the error. No Bybit event was delivered.

Quotes: 0

Trades: 0

L2 book: 0

Session: `20260929T124105Z-d203df31`. Requested runtime 90 seconds. Wall clock 90.1 seconds.

Runtime: 90.1 seconds

Total events: 0

Events/sec: 0

Reconnects: 0

Invalid events: 0

Out-of-order: 0

Stale periods: 0

Book desyncs: 0

Health remained `STARTING`.

Internal latency: not measured. All stage counts are 0.

receive→normalize p50/p95/p99/max: n/a

normalize→aggregate p50/p95/p99/max: n/a

aggregate→recorder p50/p95/p99/max: n/a

total p50/p95/p99/max: n/a

A separate public websocket handshake to `wss://stream.bybit.com/v5/public/spot` returned `HTTP/1.1 101 Switching Protocols` and a pong frame. That check did not pass through `NautilusMarketSource` and is not a Bybit sensor pass.

Binance and Bybit were not run together. A combined node would still need the Bybit REST instrument load that returns 403 here.

Real-machine commands, from `btc-intelligence/`:

```bash
python scripts/run_sensor.py --venues BINANCE --seconds 1800 --output records
python scripts/run_sensor.py --venues BYBIT --seconds 1800 --output records
python scripts/run_sensor.py --venues BINANCE,BYBIT --seconds 1800 --output records
```

## MULTI-EXCHANGE

Unified model: PASS offline. Binance and Bybit `QuoteTick`, `TradeTick`, and `OrderBookDeltas` normalize to the same `NormalizedMarketEvent` fields. There is one aggregator.

BTCMarketState: `MultiVenueBTCMarketState` holds one view per venue plus an aggregate. Phase 0 `BTCMarketState` is unchanged. Live state was observed for Binance only.

Reference price: median of healthy mid prices. One healthy venue, so the reference price is that mid: 84402.175. It is a description of the book, not a fair value, a prediction, or an expected price.

Cross-exchange deviation: not measured live. With one healthy mid the deviation is empty. Unit tests cover two healthy mids (`max_mid - min_mid`) and exclusion of a desynced venue.

Health filtering: PASS offline, and the live Binance book contributed only while `HEALTHY`.

PASS/FAIL: live multi-exchange FAIL. Bybit produced no market events in this environment.

## RECORDER

Format: append-only JSONL, schema `btc-intelligence.sensor.v1`

Async: one writer thread. The callback only enqueues.

Bounded: queue capacity 100,000

Dropped events: 0 on the Binance soak. Unit tests cover a full queue.

Replay readiness: each row has session id, venue, instrument, kind, sequence, health, the separate timestamps, and the quote, trade, or book-delta payload. Raw venue JSON is not stored.

## TESTS

Previous tests: 49

New tests: 13

Total: 62

PASS/FAIL: PASS. `pytest` excludes the `integration` marker. No unit test opens a socket.

## SECURITY

API keys: none. Public clients pass empty credentials.

Trading: disabled. `exec_clients` is empty. Reconciliation is off. No order was submitted.

Private streams: not opened. Bybit uses `new_public` only.

Secrets: none added to git.

## FORK-GATE FINDINGS

None that meet the fork bar.

Evidence reviewed and left in place:

1. Binance spot trade event time `E` is parsed and dropped. Trade time `T` remains on `TradeTick.ts_event`.
2. Binance diff first update id `U` is not on `OrderBookDeltas`. The stored sequence is the final update id `u`. A gap of the form `U == previous_u + 1` cannot be proven, so a forward jump is applied and a backwards sequence marks the venue `DESYNCED`.
3. Binance bookTicker without `T`, and depth snapshots, set `ts_event` from `ts_init`. The sensor stores no exchange time in that case. The 30 minute run confirmed this for all 201,159 quotes.
4. Bybit order-book `cts` is on the v1.231.0 websocket struct and is not copied onto `OrderBookDeltas`. Message time `ts` remains.
5. Bybit trade envelope `ts` is not on `TradeTick`. Trade time `T` remains.

Workaround: leave the missing fields empty. The public objects are enough to ingest and validate the book. Upstream 1.231.0 does not expose those fields through an extension point on the Python data objects. That is not a measured internal-latency bottleneck and not a defect that blocks the sensor. Nautilus was not patched.

The Bybit HTTP 403 is a network block, not a Nautilus defect.

## KNOWN LIMITATIONS

- Live Bybit and the combined soak did not run here.
- Local receive time is the actor callback, not the kernel socket timestamp. `ts_init` stays in its own field.
- Binance spot bookTicker in this run had no exchange transaction time.
- Binance diff continuity is final-update-id only.
- Quote and book tops often differ because bookTicker is faster than depth at 100 ms. The book remains the aggregate source.
- The recorded enqueue timestamp is the clock immediately before `put_nowait`. The latency sample includes that call.
- Internal p50 total was about 1.16 ms on this VM. No rewrite was attempted.
- Coinbase still has no adapter in 1.231.0.

## RECOMMENDED PHASE 2

Do not start it from this report.

When a network can reach Bybit REST, run the 1,800 second Bybit command and then the combined command. Keep the same domain model. Use the JSONL sessions to study the observed timestamp delta before calling any of it network latency. Still no signals, no predictions, and no orders.
