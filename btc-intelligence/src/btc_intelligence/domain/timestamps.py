"""Timestamps carried with market and decision objects.

Durations are recorded in nanoseconds so later measurement can use them.
Phase 0 does not claim a latency budget.
"""

from dataclasses import dataclass, replace


@dataclass(frozen=True, slots=True)
class EventTimestamps:
    """Point-in-time marks for one pipeline pass.

    ``exchange_timestamp_ns`` comes from the venue event when the source
    exposes one. ``local_receive_timestamp_ns`` is stamped by BTC-Intelligence
    when it accepts the object. ``source_init_timestamp_ns`` preserves an
    upstream initialization time (Nautilus ``ts_init``) without treating it
    as local receive time.
    """

    exchange_timestamp_ns: int | None = None
    local_receive_timestamp_ns: int | None = None
    normalized_timestamp_ns: int | None = None
    aggregated_timestamp_ns: int | None = None
    signal_timestamp_ns: int | None = None
    decision_timestamp_ns: int | None = None
    execution_request_timestamp_ns: int | None = None
    source_init_timestamp_ns: int | None = None

    def __post_init__(self) -> None:
        for name in (
            "exchange_timestamp_ns",
            "local_receive_timestamp_ns",
            "normalized_timestamp_ns",
            "aggregated_timestamp_ns",
            "signal_timestamp_ns",
            "decision_timestamp_ns",
            "execution_request_timestamp_ns",
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
