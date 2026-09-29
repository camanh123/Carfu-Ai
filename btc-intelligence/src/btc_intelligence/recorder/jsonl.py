"""Append-only recorder. The market callback only enqueues."""

from dataclasses import dataclass
import json
from pathlib import Path
import queue
import threading
from typing import Any

from btc_intelligence.domain.events import MarketEventKind, NormalizedMarketEvent


SCHEMA_VERSION = "btc-intelligence.sensor.v1"


@dataclass(frozen=True, slots=True)
class SensorRecord:
    session_id: str
    venue: str
    instrument: str
    kind: str
    exchange_event_ns: int | None
    exchange_transaction_ns: int | None
    local_receive_ns: int | None
    nautilus_ts_init_ns: int | None
    normalized_ns: int | None
    aggregated_ns: int | None
    enqueued_ns: int
    sequence: int | None
    health: str
    payload: tuple[Any, ...]


class AsyncJsonlRecorder:
    """Bounded queue plus one writer thread.

    ``enqueue`` does not write to disk. A full queue increments ``dropped``
    and returns False.
    """

    def __init__(self, path: Path, *, session_id: str, max_queue: int = 100_000, start: bool = True) -> None:
        if max_queue <= 0:
            raise ValueError("max_queue must be > 0")
        self.path = path
        self.session_id = session_id
        self._queue: queue.Queue[SensorRecord | None] = queue.Queue(maxsize=max_queue)
        self._dropped = 0
        self._written = 0
        self._closed = False
        self._lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._thread = threading.Thread(target=self._run, name="btc-sensor-recorder", daemon=True)
        if start:
            self._thread.start()

    @property
    def dropped(self) -> int:
        with self._lock:
            return self._dropped

    @property
    def written(self) -> int:
        return self._written

    def enqueue(self, record: SensorRecord) -> bool:
        if self._closed:
            raise RuntimeError("recorder is shut down")
        try:
            self._queue.put_nowait(record)
        except queue.Full:
            with self._lock:
                self._dropped += 1
            return False
        return True

    def close(self, timeout_s: float = 5.0) -> None:
        if self._closed:
            return
        self._closed = True
        if self._thread.ident is None:
            self._thread.start()
        self._queue.put(None)
        self._thread.join(timeout=timeout_s)
        if self._thread.is_alive():
            raise TimeoutError("recorder thread did not stop")

    def _run(self) -> None:
        with self.path.open("a", encoding="utf-8") as handle:
            while True:
                item = self._queue.get()
                if item is None:
                    handle.flush()
                    return
                handle.write(json.dumps(_row(item), separators=(",", ":")) + "\n")
                self._written += 1
                if self._queue.empty():
                    handle.flush()


def record_from_event(
    event: NormalizedMarketEvent,
    *,
    session_id: str,
    health: str,
    aggregated_ns: int,
    enqueued_ns: int,
) -> SensorRecord:
    timestamps = event.timestamps
    return SensorRecord(
        session_id=session_id,
        venue=event.instrument.venue.value,
        instrument=str(event.instrument),
        kind=event.kind.value,
        exchange_event_ns=timestamps.exchange_event_ns,
        exchange_transaction_ns=timestamps.exchange_transaction_ns,
        local_receive_ns=timestamps.local_receive_timestamp_ns,
        nautilus_ts_init_ns=timestamps.source_init_timestamp_ns,
        normalized_ns=timestamps.normalized_timestamp_ns,
        aggregated_ns=aggregated_ns,
        enqueued_ns=enqueued_ns,
        sequence=_sequence(event),
        health=health,
        payload=_payload(event),
    )


def _sequence(event: NormalizedMarketEvent) -> int | None:
    if event.book_delta is not None:
        return event.book_delta.sequence
    if event.book is not None:
        return event.book.sequence
    return None


def _payload(event: NormalizedMarketEvent) -> tuple[Any, ...]:
    if event.kind is MarketEventKind.QUOTE and event.quote is not None:
        quote = event.quote
        return (str(quote.bid_price), str(quote.bid_size), str(quote.ask_price), str(quote.ask_size))
    if event.kind is MarketEventKind.TRADE and event.trade is not None:
        trade = event.trade
        return (str(trade.price), str(trade.size), trade.side.value, trade.trade_id)
    if event.kind is MarketEventKind.BOOK_DELTA and event.book_delta is not None:
        return tuple(
            (
                delta.action.value,
                None if delta.side is None else delta.side.value,
                None if delta.price is None else str(delta.price),
                None if delta.size is None else str(delta.size),
            )
            for delta in event.book_delta.deltas
        )
    return ()


def _row(record: SensorRecord) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "session_id": record.session_id,
        "venue": record.venue,
        "instrument": record.instrument,
        "kind": record.kind,
        "exchange_event_ns": record.exchange_event_ns,
        "exchange_transaction_ns": record.exchange_transaction_ns,
        "local_receive_ns": record.local_receive_ns,
        "nautilus_ts_init_ns": record.nautilus_ts_init_ns,
        "normalized_ns": record.normalized_ns,
        "aggregated_ns": record.aggregated_ns,
        "enqueued_ns": record.enqueued_ns,
        "sequence": record.sequence,
        "health": record.health,
        "payload": record.payload,
    }
