# Phase 1 NautilusTrader 1.231.0 public-data audit

Audited from the installed wheel `nautilus_trader-1.231.0` (CPython 3.12) and the matching `v1.231.0` Bybit parser. Nautilus source was not modified.

## Binance spot data client

`BinanceDataClientConfig` with `api_key=None` and `api_secret=None` is public market data only. The default account type is `SPOT`. This phase sets `use_agg_trade_ticks=False`, so the spot trade subscription is the raw `@trade` stream. Futures would force `@aggTrade`; that path is not used.

Subscriptions used:

| Project need | Nautilus call | Venue stream |
| --- | --- | --- |
| Best bid/ask and sizes | `subscribe_quote_ticks` | `bookTicker` |
| Trades and aggressor | `subscribe_trade_ticks` | spot `@trade` |
| L2 changes | `subscribe_order_book_deltas`, `BookType.L2_MBP`, depth `1000` | diff depth `@depth@100ms` plus an HTTP snapshot |

Depth `0` means full book and is translated to `1000`. Depth `<= 20` uses the partial book stream, which has no event timestamp. Depth `1000` stays on the diff-plus-snapshot path. `L3_MBO` is rejected by the adapter.

The HTTP snapshot and the websocket client accept `base_url_http` and `base_url_ws`. The websocket URL is `base + /stream?streams=...`. This environment receives HTTP 451 from `api.binance.com`, and HTTP 200 from `https://data-api.binance.vision`. The sensor therefore points the public client at `https://data-api.binance.vision` and `wss://data-stream.binance.vision`.

Instrument id: `BTCUSDT.BINANCE`.

## Bybit public data client

`BybitDataClientConfig.api_key is None` makes the Rust HTTP client look for `BYBIT_API_KEY` / `BYBIT_API_SECRET`. Those variables are unset. The data client constructs websockets with `BybitWebSocketClient.new_public` only. Product type is `SPOT` only. No private stream is opened.

The spot symbol must carry the product suffix. `bybit_product_type_from_symbol("BTCUSDT")` fails. `BTCUSDT-SPOT` resolves to spot. Instrument id: `BTCUSDT-SPOT.BYBIT`.

| Project need | Nautilus call | Venue stream |
| --- | --- | --- |
| Best bid/ask and sizes | `subscribe_quote_ticks` | public spot `orderbook.1` (the spot ticker has no bid/ask) |
| Trades and aggressor | `subscribe_trade_ticks` | public trades |
| L2 changes | `subscribe_order_book_deltas`, `BookType.L2_MBP`, depth `50` | public `orderbook.50` |

Depth `0` becomes `50`. Supported public depths include `1`, `50`, and `200`. A non-`L2_MBP` book subscription is skipped.

Instrument loading uses the Bybit HTTP API. This environment receives CloudFront HTTP 403 from the Bybit REST hosts, so the Nautilus client cannot finish `_connect` here even though the public websocket handshake answers. That is a network restriction, not a Nautilus defect.

## Timestamp semantics

Nautilus `ts_event` and `ts_init` are nanoseconds. BTC-Intelligence stamps `local_receive_timestamp_ns` when the actor accepts the object. `source_init_timestamp_ns` keeps `ts_init`. Those clocks are never copied onto each other.

| Object | What `ts_event` actually is | Project fields |
| --- | --- | --- |
| Binance spot trade | Trade time `T`. Event time `E` is parsed and then dropped. | `exchange_timestamp_ns` and `exchange_transaction_ns` = `ts_event`. `exchange_event_ns` stays empty. |
| Binance spot bookTicker | Transaction time `T` when present. If `T` is absent, the adapter sets `ts_event` from `ts_init`. | When `ts_event == ts_init`, all exchange fields stay empty. Otherwise `exchange_event_ns` = `ts_event`. |
| Binance diff depth | Spot event time `E`. Futures transaction time `T` is not used for spot. Final update id `u` becomes the delta sequence. First update id `U` is discarded. | `exchange_event_ns` = `ts_event` when it differs from `ts_init`. |
| Binance HTTP / partial depth snapshot | No exchange event time. `ts_event` is set to `ts_init`. | Exchange fields stay empty. |
| Bybit public trade | Trade field `T`. Envelope `ts` is not copied onto the tick. | `exchange_transaction_ns` = `ts_event`. `exchange_event_ns` stays empty. |
| Bybit order book and the spot top-of-book quote | Message time `msg.ts`. Matching-engine field `cts` is present on `BybitWsOrderbookDepthMsg` and is not passed to `OrderBookDeltas` or `QuoteTick`. | `exchange_event_ns` = `ts_event`. `exchange_transaction_ns` stays empty. |

The difference `local_receive_ns - exchange_event_ns` (or trade time when event time is absent) is reported as an observed timestamp delta. It includes adapter and actor queueing, and the clocks are not treated as synchronized, so it is not called network latency.

## Order book integrity available through the public API

`OrderBookDeltas.is_snapshot` is true when the batch starts with `CLEAR`. Binance diff continuity cannot be verified as `U == previous_u + 1` because `U` is not on the Python object. The sensor applies a forward sequence jump and treats a backwards sequence as loss of sync. Bybit continuity uses `data.seq`, which Nautilus stores as `OrderBookDelta.sequence`. Bybit update id `u` is stored as `BookOrder.order_id` and is not copied into the project sequence.

Aggressor mapping for both venues is reliable on the public trade object: buyer-is-maker means `SELLER`, otherwise `BUYER`. `NO_AGGRESSOR` stays `UNKNOWN`.

## Reconnect

Binance rebuilds a book with `_order_book_snapshot_then_deltas` after subscribe, buffering diffs until the snapshot sequence is applied. The sensor does not hook an adapter-private reconnect callback. A venue with no events for 5 seconds becomes `STALE` and is removed from the aggregate. At 30 seconds it becomes `DISCONNECTED` and its book is untrusted. The next event moves it to `RECOVERING`. A later trusted snapshot returns it to `HEALTHY` and increments the reconnect count.

## Limitations that do not justify a fork

1. Binance spot trade event time `E` is discarded. Trade time `T` remains.
2. Binance diff first update id `U` is discarded, so a gap is not provable from the public object.
3. Binance bookTicker without `T`, and depth snapshots, copy `ts_init` into `ts_event`. The sensor drops that value instead of labeling it as an exchange time.
4. Bybit order book `cts` is parsed on the wire struct and then dropped. Message time `ts` remains.
5. Bybit trade envelope `ts` is not on `TradeTick`. Trade time `T` remains.
6. The installed wheel has no Coinbase adapter module. Coinbase stays configuration-only.

Each discarded field is left empty. The public `QuoteTick`, `TradeTick`, and `OrderBookDeltas` types are sufficient to ingest, normalize, and validate the book. No Nautilus file was patched.
