"""Deterministic Phase 1 sensor tests. None of these open a network connection."""

from decimal import Decimal
from pathlib import Path

import pytest
from nautilus_trader.model.data import BookOrder, OrderBookDelta, OrderBookDeltas, QuoteTick, TradeTick
from nautilus_trader.model.enums import AggressorSide, BookAction, OrderSide
from nautilus_trader.model.identifiers import InstrumentId, TradeId
from nautilus_trader.model.objects import Price, Quantity

from btc_intelligence.diagnostics.distribution import LatencyDistribution
from btc_intelligence.domain.events import BookDelta
from btc_intelligence.domain.health import VenueHealth
from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.book import L2Book
from btc_intelligence.market.multi import MultiExchangeAggregator
from btc_intelligence.market.nautilus_source import NautilusMarketSource
from btc_intelligence.market.normalize import book_delta_from_parts, normalize_book_deltas, normalize_quote
from btc_intelligence.recorder.jsonl import SCHEMA_VERSION, AsyncJsonlRecorder, record_from_event
from btc_intelligence.sensor.node import build_trading_node_config
from btc_intelligence.sensor.pipeline import SensorPipeline
from btc_intelligence.sensor.settings import default_phase1_path, load_phase1


class StepClock:
    def __init__(self) -> None:
        self.now = 0

    def now_ns(self) -> int:
        self.now += 1_000
        return self.now


def _snapshot(venue: str, sequence: int, bid: str = "100", ask: str = "101", *, local_ns: int = 5_000) -> object:
    symbol = "BTCUSDT" if venue == "BINANCE" else "BTCUSDT-SPOT"
    deltas = (
        book_delta_from_parts(action="CLEAR", side=None, price=None, size=None, sequence=sequence),
        book_delta_from_parts(action="ADD", side="BID", price=bid, size="1.5", sequence=sequence),
        book_delta_from_parts(action="ADD", side="ASK", price=ask, size="2.5", sequence=sequence),
    )
    return normalize_book_deltas(
        symbol=symbol,
        venue=venue,
        deltas=deltas,
        sequence=sequence,
        is_snapshot=True,
        exchange_timestamp_ns=1_700_000_000_000_000_000,
        exchange_event_ns=1_700_000_000_000_000_000,
        local_receive_timestamp_ns=local_ns,
        normalized_timestamp_ns=local_ns + 1,
        source_init_timestamp_ns=local_ns + 2,
    )


def _delta(venue: str, sequence: int, *, bid: str = "100", ask: str = "101") -> object:
    symbol = "BTCUSDT" if venue == "BINANCE" else "BTCUSDT-SPOT"
    deltas = (
        book_delta_from_parts(action="UPDATE", side="BID", price=bid, size="1.5", sequence=sequence),
        book_delta_from_parts(action="UPDATE", side="ASK", price=ask, size="2.5", sequence=sequence),
    )
    return normalize_book_deltas(
        symbol=symbol,
        venue=venue,
        deltas=deltas,
        sequence=sequence,
        is_snapshot=False,
        exchange_timestamp_ns=1_700_000_000_000_000_000,
        exchange_event_ns=1_700_000_000_000_000_000,
        local_receive_timestamp_ns=6_000,
        normalized_timestamp_ns=6_001,
        source_init_timestamp_ns=6_002,
    )


def _aggregator() -> MultiExchangeAggregator:
    return MultiExchangeAggregator(
        {
            ExchangeId.BINANCE: InstrumentRef.parse("BTCUSDT", "BINANCE"),
            ExchangeId.BYBIT: InstrumentRef.parse("BTCUSDT-SPOT", "BYBIT"),
        },
        publish_depth=50,
        stale_after_ns=1_000,
        disconnect_after_ns=5_000,
    )


def _nautilus_deltas(instrument: str, *, ts_event: int, ts_init: int, sequence: int) -> OrderBookDeltas:
    instrument_id = InstrumentId.from_str(instrument)
    bid = BookOrder(OrderSide.BUY, Price.from_str("64000.10"), Quantity.from_str("1.25"), 0)
    ask = BookOrder(OrderSide.SELL, Price.from_str("64000.50"), Quantity.from_str("0.80"), 0)
    return OrderBookDeltas(
        instrument_id,
        [
            OrderBookDelta.clear(instrument_id, sequence, ts_event, ts_init),
            OrderBookDelta(instrument_id, BookAction.ADD, bid, 0, sequence, ts_event, ts_init),
            OrderBookDelta(instrument_id, BookAction.ADD, ask, 0, sequence, ts_event, ts_init),
        ],
    )


def test_public_node_config_has_no_execution_clients() -> None:
    config = load_phase1(default_phase1_path())
    node_config = build_trading_node_config(config.select(("BINANCE", "BYBIT")))
    assert list(node_config.data_clients) == ["BINANCE", "BYBIT"]
    assert node_config.exec_clients == {}
    assert node_config.exec_engine.reconciliation is False


def test_phase1_config_stays_public_and_selects_venues() -> None:
    config = load_phase1(default_phase1_path())
    assert config.live_trading is False
    selected = config.select(("BINANCE", "BYBIT"))
    assert selected[0].symbol == "BTCUSDT"
    assert selected[0].book_depth == 1000
    assert selected[1].symbol == "BTCUSDT-SPOT"
    assert selected[1].book_depth == 50


def test_binance_and_bybit_quotes_trades_and_books_share_one_model() -> None:
    source = NautilusMarketSource()
    cases = (
        ("BTCUSDT.BINANCE", "BINANCE", "BTCUSDT"),
        ("BTCUSDT-SPOT.BYBIT", "BYBIT", "BTCUSDT-SPOT"),
    )
    for instrument, venue, symbol in cases:
        instrument_id = InstrumentId.from_str(instrument)
        exchange_ns = 1_700_000_000_000_000_000
        init_ns = exchange_ns + 40
        quote = QuoteTick(
            instrument_id=instrument_id,
            bid_price=Price.from_str("64000.10"),
            ask_price=Price.from_str("64000.50"),
            bid_size=Quantity.from_str("1.25"),
            ask_size=Quantity.from_str("0.80"),
            ts_event=exchange_ns,
            ts_init=init_ns,
        )
        trade = TradeTick(
            instrument_id=instrument_id,
            price=Price.from_str("64000.20"),
            size=Quantity.from_str("0.010"),
            aggressor_side=AggressorSide.BUYER,
            trade_id=TradeId("trade-1"),
            ts_event=exchange_ns + 5,
            ts_init=init_ns,
        )
        book = _nautilus_deltas(instrument, ts_event=exchange_ns + 9, ts_init=init_ns, sequence=77)
        quote_event = source.normalize(quote, local_receive_timestamp_ns=100, normalized_timestamp_ns=110)
        trade_event = source.normalize(trade, local_receive_timestamp_ns=120, normalized_timestamp_ns=130)
        book_event = source.normalize(book, local_receive_timestamp_ns=140, normalized_timestamp_ns=150)

        assert quote_event.instrument == InstrumentRef.parse(symbol, venue)
        assert quote_event.quote is not None
        assert quote_event.quote.bid_price == Decimal("64000.10")
        assert quote_event.quote.ask_size == Decimal("0.80")
        assert quote_event.timestamps.exchange_event_ns == exchange_ns
        assert quote_event.timestamps.exchange_transaction_ns is None
        assert quote_event.timestamps.local_receive_timestamp_ns == 100
        assert quote_event.timestamps.source_init_timestamp_ns == init_ns
        assert quote_event.timestamps.exchange_timestamp_ns != quote_event.timestamps.local_receive_timestamp_ns

        assert trade_event.trade is not None
        assert trade_event.trade.side is TradeSide.BUY
        assert trade_event.timestamps.exchange_transaction_ns == exchange_ns + 5
        assert trade_event.timestamps.exchange_event_ns is None
        assert trade_event.timestamps.local_receive_timestamp_ns == 120
        assert trade_event.timestamps.source_init_timestamp_ns == init_ns

        assert book_event.book_delta is not None
        assert book_event.book_delta.is_snapshot is True
        assert book_event.book_delta.sequence == 77
        assert book_event.book_delta.deltas[0].action.value == "CLEAR"
        assert book_event.book_delta.deltas[1].side is not None
        assert book_event.book_delta.deltas[1].side.value == "BID"
        assert book_event.book_delta.deltas[2].side is not None
        assert book_event.book_delta.deltas[2].side.value == "ASK"
        assert book_event.timestamps.exchange_event_ns == exchange_ns + 9
        assert book_event.timestamps.local_receive_timestamp_ns == 140
        assert book_event.timestamps.source_init_timestamp_ns == init_ns


def test_binance_substituted_clock_is_not_stored_as_exchange_time() -> None:
    instrument_id = InstrumentId.from_str("BTCUSDT.BINANCE")
    same = 1_700_000_000_000_000_000
    quote = QuoteTick(
        instrument_id=instrument_id,
        bid_price=Price.from_str("1"),
        ask_price=Price.from_str("2"),
        bid_size=Quantity.from_str("1"),
        ask_size=Quantity.from_str("1"),
        ts_event=same,
        ts_init=same,
    )
    event = NautilusMarketSource().normalize(quote, local_receive_timestamp_ns=50, normalized_timestamp_ns=60)
    assert event.timestamps.exchange_timestamp_ns is None
    assert event.timestamps.exchange_event_ns is None
    assert event.timestamps.exchange_transaction_ns is None
    assert event.timestamps.source_init_timestamp_ns == same
    assert event.timestamps.local_receive_timestamp_ns == 50


def test_book_delta_rejects_non_positive_price() -> None:
    from btc_intelligence.domain.events import BookSide, BookUpdateAction

    with pytest.raises(ValueError):
        BookDelta(
            action=BookUpdateAction.ADD,
            side=BookSide.BID,
            price=Decimal("0"),
            size=Decimal("1"),
        )


def test_valid_book_crossed_book_sequence_and_depth() -> None:
    book = L2Book(publish_depth=2)
    event = _snapshot("BINANCE", 10)
    result = book.apply(event.book_delta)
    assert result.trusted is True
    assert book.best_bid() == (Decimal("100"), Decimal("1.5"))
    assert book.best_ask() == (Decimal("101"), Decimal("2.5"))
    assert len(book.top_bids(50)) == 1

    duplicate = book.apply(_delta("BINANCE", 10).book_delta)
    assert duplicate.duplicate is True
    assert book.sequence == 10

    jumped = book.apply(_delta("BINANCE", 12, bid="100.5", ask="101.5").book_delta)
    assert jumped.trusted is True
    assert book.best_bid()[0] == Decimal("100.5")

    backwards = book.apply(_delta("BINANCE", 11).book_delta)
    assert backwards.out_of_order is True
    assert book.trusted is False
    assert book.best_bid()[0] == Decimal("100.5")

    crossed = L2Book(publish_depth=50)
    crossed_result = crossed.apply(_snapshot("BINANCE", 1, bid="102", ask="101").book_delta)
    assert crossed_result.crossed is True
    assert crossed_result.trusted is False


def test_many_levels_publish_only_the_configured_depth() -> None:
    levels = [book_delta_from_parts(action="CLEAR", side=None, price=None, size=None, sequence=1)]
    for index in range(60):
        levels.append(
            book_delta_from_parts(
                action="ADD",
                side="BID",
                price=str(Decimal("100") - index),
                size="1",
                sequence=1,
            )
        )
    levels.append(book_delta_from_parts(action="ADD", side="ASK", price="200", size="1", sequence=1))
    event = normalize_book_deltas(
        symbol="BTCUSDT",
        venue="BINANCE",
        deltas=tuple(levels),
        sequence=1,
        is_snapshot=True,
        exchange_timestamp_ns=1,
        local_receive_timestamp_ns=2,
        normalized_timestamp_ns=3,
    )
    book = L2Book(publish_depth=50)
    assert book.apply(event.book_delta).trusted is True
    assert len(book.top_bids()) == 50
    assert book.top_bids()[0].price == Decimal("100")
    assert book.best_bid()[0] == Decimal("100")


def test_venue_health_stale_disconnect_and_exclusion() -> None:
    aggregator = _aggregator()
    state = aggregator.apply(_snapshot("BINANCE", 1, local_ns=100), aggregated_ns=100)
    binance = state.venue(ExchangeId.BINANCE)
    assert binance is not None
    assert binance.health is VenueHealth.HEALTHY
    assert binance.contributes is True
    assert binance.mid_price == Decimal("100.5")
    assert state.aggregate.reference_price == Decimal("100.5")
    assert state.aggregate.healthy_exchange_count == 1

    stale = aggregator.observe_clock(1_100)
    assert stale.venue(ExchangeId.BINANCE).health is VenueHealth.STALE
    assert stale.aggregate.healthy_exchange_count == 0
    assert stale.aggregate.reference_price is None

    disconnected = aggregator.observe_clock(5_100)
    assert disconnected.venue(ExchangeId.BINANCE).health is VenueHealth.DISCONNECTED
    assert aggregator.quality(ExchangeId.BINANCE).stale_periods == 1

    recovered = aggregator.apply(_snapshot("BINANCE", 2, local_ns=5_200), aggregated_ns=5_200)
    assert recovered.venue(ExchangeId.BINANCE).health is VenueHealth.HEALTHY
    assert aggregator.quality(ExchangeId.BINANCE).reconnect_count == 1


def test_desynced_venue_is_excluded_from_the_reference_price() -> None:
    aggregator = _aggregator()
    aggregator.apply(_snapshot("BINANCE", 1, bid="100", ask="102", local_ns=10), aggregated_ns=10)
    aggregator.apply(_snapshot("BYBIT", 1, bid="200", ask="202", local_ns=20), aggregated_ns=20)
    both = aggregator.snapshot(20)
    assert both.aggregate.healthy_exchange_count == 2
    assert both.aggregate.reference_price == Decimal("151")
    assert both.aggregate.cross_exchange_deviation == Decimal("100")
    assert both.aggregate.freshest_exchange is ExchangeId.BYBIT

    crossed = aggregator.apply(_snapshot("BYBIT", 2, bid="300", ask="100", local_ns=30), aggregated_ns=30)
    assert crossed.venue(ExchangeId.BYBIT).health is VenueHealth.DESYNCED
    assert crossed.venue(ExchangeId.BYBIT).contributes is False
    assert crossed.venue(ExchangeId.BYBIT).best_bid is None
    assert crossed.aggregate.healthy_exchange_count == 1
    assert crossed.aggregate.reference_price == Decimal("101")
    assert crossed.aggregate.cross_exchange_deviation is None
    assert aggregator.quality(ExchangeId.BYBIT).desync_count == 1
    assert aggregator.quality(ExchangeId.BYBIT).invalid_events == 0


def test_out_of_order_book_marks_the_venue_desynced() -> None:
    aggregator = _aggregator()
    aggregator.apply(_snapshot("BINANCE", 10), aggregated_ns=1)
    state = aggregator.apply(_delta("BINANCE", 9), aggregated_ns=2)
    assert state.venue(ExchangeId.BINANCE).health is VenueHealth.DESYNCED
    assert state.aggregate.healthy_exchange_count == 0
    assert aggregator.quality(ExchangeId.BINANCE).out_of_order == 1


def test_recorder_enqueue_overflow_and_shutdown(tmp_path: Path) -> None:
    path = tmp_path / "events.jsonl"
    bounded = AsyncJsonlRecorder(path, session_id="session-a", max_queue=1, start=False)
    event = normalize_quote(
        symbol="BTCUSDT",
        venue="BINANCE",
        bid_price="1",
        ask_price="2",
        bid_size="1",
        ask_size="1",
        exchange_timestamp_ns=10,
        local_receive_timestamp_ns=20,
        normalized_timestamp_ns=30,
        source_init_timestamp_ns=15,
        exchange_event_ns=10,
    )
    first = record_from_event(event, session_id="session-a", health="HEALTHY", aggregated_ns=40, enqueued_ns=41)
    second = record_from_event(event, session_id="session-a", health="HEALTHY", aggregated_ns=42, enqueued_ns=43)
    assert bounded.enqueue(first) is True
    assert bounded.enqueue(second) is False
    assert bounded.dropped == 1
    bounded.close()
    lines = path.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    assert SCHEMA_VERSION in lines[0]
    assert "session-a" in lines[0]
    assert '"venue":"BINANCE"' in lines[0]

    live = AsyncJsonlRecorder(tmp_path / "live.jsonl", session_id="session-b", max_queue=8)
    assert live.enqueue(first) is True
    live.close()
    assert not live._thread.is_alive()
    assert live.written == 1
    with pytest.raises(RuntimeError):
        live.enqueue(first)


def test_pipeline_measures_stages_and_preserves_receive_time(tmp_path: Path) -> None:
    clock = StepClock()
    recorder = AsyncJsonlRecorder(tmp_path / "pipe.jsonl", session_id="pipe", max_queue=8)
    pipeline = SensorPipeline(
        source=NautilusMarketSource(),
        aggregator=_aggregator(),
        recorder=recorder,
        session_id="pipe",
        clock=clock,
    )
    instrument_id = InstrumentId.from_str("BTCUSDT.BINANCE")
    quote = QuoteTick(
        instrument_id=instrument_id,
        bid_price=Price.from_str("10"),
        ask_price=Price.from_str("11"),
        bid_size=Quantity.from_str("1"),
        ask_size=Quantity.from_str("1"),
        ts_event=500,
        ts_init=700,
    )
    state = pipeline.handle(quote, local_receive_ns=100)
    assert state is not None
    summaries = pipeline.summaries()
    assert summaries.receive_to_normalize.count == 1
    assert summaries.normalize_to_aggregate.count == 1
    assert summaries.aggregate_to_recorder.count == 1
    assert summaries.total_internal.count == 1
    assert summaries.observed_timestamp_delta.count == 1
    assert summaries.observed_timestamp_delta.max_ns == 100 - 500
    assert pipeline.handle(object()) is None
    assert pipeline.invalid == 1
    pipeline.close()
    assert recorder.written == 1


def test_latency_distribution_reports_percentiles_and_withholds_p999_until_enough_samples() -> None:
    small = LatencyDistribution()
    small.add(10)
    small.add(20)
    summary = small.summary()
    assert summary.count == 2
    assert summary.p50_ns == 15
    assert summary.max_ns == 20
    assert summary.p99_9_ns is None

    large = LatencyDistribution()
    for value in range(1, 1001):
        large.add(value)
    full = large.summary()
    assert full.count == 1000
    assert full.p99_9_ns is not None
    assert full.max_ns == 1000
    assert full.mean_ns == 500.5
