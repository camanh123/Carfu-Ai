"""Timestamps carried with market and decision objects.

Durations are recorded in nanoseconds so later measurement can use them.
Phase 0 does not claim a latency budget.
"""

from dataclasses import dataclass, replace


@dataclass(frozen=True, slots=True)
class EventTimestamps:
    """Point-in-time marks for one pipeline pass.

    ``exchange_event_ns`` is an exchange event/message time when the adapter
    actually exposes one. ``exchange_transaction_ns`` is a trade or matching
    time when that is a different field. ``exchange_timestamp_ns`` is the
    exchange time used by Phase 0 and is left empty when the only candidate
    was copied from a local clock. ``local_receive_timestamp_ns`` is stamped
    by BTC-Intelligence when it accepts the object. ``source_init_timestamp_ns``
    is Nautilus ``ts_init`` and is not a local receive time. The difference
    between an exchange time and local receive time is an observed timestamp
    delta, not a measured network latency.
    """

    exchange_timestamp_ns: int | None = None
    exchange_event_ns: int | None = None
    exchange_transaction_ns: int | None = None
    local_receive_timestamp_ns: int | None = None
    normalized_timestamp_ns: int | None = None
    aggregated_timestamp_ns: int | None = None
    signal_timestamp_ns: int | None = None
    decision_timestamp_ns: int | None = None
    execution_request_timestamp_ns: int | None = None
    recorder_enqueue_ns: int | None = None
    source_init_timestamp_ns: int | None = None

    def __post_init__(self) -> None:
        for name in (
            "exchange_timestamp_ns",
            "exchange_event_ns",
            "exchange_transaction_ns",
            "local_receive_timestamp_ns",
            "normalized_timestamp_ns",
            "aggregated_timestamp_ns",
            "signal_timestamp_ns",
            "decision_timestamp_ns",
            "execution_request_timestamp_ns",
            "recorder_enqueue_ns",
            "source_init_timestamp_ns",
        ):
            value = getattr(self, name)
            if value is None:
                continue
            if isinstance(value, bool) or not isinstance(value, int):
                raise ValueError(f"{name} must be an int nanosecond timestamp")
            if value < 0:
                raise ValueError(f"{name} must be >= 0")

    def stamp(self, **fields: int | None) -> "EventTimestamps":
        unknown = set(fields) - set(self.__dataclass_fields__)
        if unknown:
            raise ValueError(f"unknown timestamp fields: {sorted(unknown)}")
        return replace(self, **fields)
