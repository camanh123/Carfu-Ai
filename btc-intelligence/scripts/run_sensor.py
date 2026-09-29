"""Run the public BTC market sensor. No credentials and no order clients."""

from pathlib import Path
import argparse
import json
import threading
import time
import uuid

from btc_intelligence.diagnostics.distribution import DistributionSummary
from btc_intelligence.domain.identifiers import InstrumentRef
from btc_intelligence.market.multi import MultiExchangeAggregator
from btc_intelligence.market.nautilus_source import NautilusMarketSource
from btc_intelligence.recorder.jsonl import SCHEMA_VERSION, AsyncJsonlRecorder
from btc_intelligence.sensor.node import build_public_node
from btc_intelligence.sensor.pipeline import SensorPipeline
from btc_intelligence.sensor.settings import Phase1Config, VenueFeed, default_phase1_path, load_phase1


def main() -> None:
    parser = argparse.ArgumentParser(description="Public Binance/Bybit BTC market sensor")
    parser.add_argument("--venues", default="BINANCE", help="Comma-separated venues, for example BINANCE,BYBIT")
    parser.add_argument("--seconds", type=int, default=60, help="How long to collect before stopping")
    parser.add_argument("--output", type=Path, default=Path("records"), help="Directory for the JSONL session")
    parser.add_argument("--config", type=Path, default=None, help="Phase 1 TOML path")
    args = parser.parse_args()
    if args.seconds <= 0:
        raise SystemExit("--seconds must be > 0")

    config = load_phase1(args.config or default_phase1_path())
    feeds = config.select(tuple(args.venues.split(",")))
    session_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:8]
    output = args.output / session_id
    output.mkdir(parents=True, exist_ok=True)
    record_path = output / "events.jsonl"

    pipeline = _pipeline(config, feeds, session_id, record_path)
    node = build_public_node(pipeline, feeds)
    started = time.time()
    stopper = threading.Thread(target=_stop_after, args=(node, pipeline, args.seconds, started), daemon=True)
    stopper.start()
    error: str | None = None
    try:
        node.run(raise_exception=True)
    except Exception as exc:
        error = f"{type(exc).__name__}: {exc}"
    finally:
        _shutdown(node, pipeline)
    elapsed = max(time.time() - started, 0.001)
    summary = _summary(pipeline, feeds, session_id, elapsed, error, record_path)
    summary_path = output / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, sort_keys=True))
    if error is not None:
        raise SystemExit(1)


def _pipeline(
    config: Phase1Config,
    feeds: tuple[VenueFeed, ...],
    session_id: str,
    record_path: Path,
) -> SensorPipeline:
    instruments = {
        feed.venue: InstrumentRef.parse(feed.symbol, feed.venue.value)
        for feed in feeds
    }
    return SensorPipeline(
        source=NautilusMarketSource(),
        aggregator=MultiExchangeAggregator(
            instruments,
            publish_depth=config.publish_depth,
            stale_after_ns=config.stale_after_ns,
            disconnect_after_ns=config.disconnect_after_ns,
        ),
        recorder=AsyncJsonlRecorder(record_path, session_id=session_id, max_queue=config.recorder_queue),
        session_id=session_id,
    )


def _stop_after(node: object, pipeline: SensorPipeline, seconds: int, started: float) -> None:
    deadline = started + seconds
    next_status = started + 30
    while time.time() < deadline:
        time.sleep(1)
        now = time.time()
        if now >= next_status:
            _print_status(pipeline, now - started)
            next_status = now + 30
    node.stop()  # type: ignore[attr-defined]


def _print_status(pipeline: SensorPipeline, elapsed_s: float) -> None:
    parts = [f"elapsed_s={elapsed_s:.0f}", f"handled={pipeline.handled}", f"invalid={pipeline.invalid}"]
    for venue in pipeline.aggregator.venues:
        quality = pipeline.aggregator.quality(venue)
        parts.append(
            f"{venue.value}:events={quality.events}:health={pipeline.aggregator.health(venue).value}:drops={pipeline.recorder.dropped}"
        )
    print(" ".join(parts), flush=True)


def _shutdown(node: object, pipeline: SensorPipeline) -> None:
    try:
        node.stop()  # type: ignore[attr-defined]
    except Exception:
        pass
    try:
        pipeline.close()
    except Exception:
        pass
    try:
        node.dispose()  # type: ignore[attr-defined]
    except Exception:
        pass


def _summary(
    pipeline: SensorPipeline,
    feeds: tuple[VenueFeed, ...],
    session_id: str,
    elapsed_s: float,
    error: str | None,
    record_path: Path,
) -> dict[str, object]:
    state = pipeline.observe_clock(time.time_ns())
    stages = pipeline.summaries()
    venues: dict[str, object] = {}
    for feed in feeds:
        quality = pipeline.aggregator.quality(feed.venue)
        view = state.venue(feed.venue)
        venues[feed.venue.value] = {
            "instrument": feed.symbol,
            "health": pipeline.aggregator.health(feed.venue).value,
            "events": quality.events,
            "events_per_sec": quality.events / elapsed_s,
            "quotes": quality.quotes,
            "trades": quality.trades,
            "book_updates": quality.book_updates,
            "invalid_events": quality.invalid_events,
            "out_of_order": quality.out_of_order,
            "duplicates": quality.duplicates,
            "book_desyncs": quality.desync_count,
            "reconnects": quality.reconnect_count,
            "stale_periods": quality.stale_periods,
            "quote_book_disagreements": quality.quote_book_disagreements,
            "substituted_exchange_clock": quality.substituted_exchange_clock,
            "freshness_ns": None if view is None else view.freshness_ns,
            "contributes": False if view is None else view.contributes,
            "mid_price": None if view is None or view.mid_price is None else str(view.mid_price),
        }
    aggregate = state.aggregate
    return {
        "session_id": session_id,
        "schema_version": SCHEMA_VERSION,
        "elapsed_s": elapsed_s,
        "error": error,
        "handled": pipeline.handled,
        "pipeline_invalid": pipeline.invalid,
        "recorder": {
            "path": str(record_path),
            "written": pipeline.recorder.written,
            "dropped": pipeline.recorder.dropped,
        },
        "venues": venues,
        "aggregate": {
            "healthy_exchange_count": aggregate.healthy_exchange_count,
            "reference_price": None if aggregate.reference_price is None else str(aggregate.reference_price),
            "min_mid": None if aggregate.min_mid is None else str(aggregate.min_mid),
            "max_mid": None if aggregate.max_mid is None else str(aggregate.max_mid),
            "cross_exchange_deviation": None
            if aggregate.cross_exchange_deviation is None
            else str(aggregate.cross_exchange_deviation),
            "freshest_exchange": None if aggregate.freshest_exchange is None else aggregate.freshest_exchange.value,
        },
        "latency_ns": {
            "receive_to_normalize": _dist(stages.receive_to_normalize),
            "normalize_to_aggregate": _dist(stages.normalize_to_aggregate),
            "aggregate_to_recorder": _dist(stages.aggregate_to_recorder),
            "total_internal": _dist(stages.total_internal),
            "observed_timestamp_delta": _dist(stages.observed_timestamp_delta),
        },
    }


def _dist(summary: DistributionSummary) -> dict[str, object]:
    return {
        "count": summary.count,
        "mean": summary.mean_ns,
        "p50": summary.p50_ns,
        "p95": summary.p95_ns,
        "p99": summary.p99_ns,
        "p99_9": summary.p99_9_ns,
        "max": summary.max_ns,
    }


if __name__ == "__main__":
    main()
