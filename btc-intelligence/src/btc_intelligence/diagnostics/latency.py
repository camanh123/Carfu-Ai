"""Compute stage durations from timestamps already captured on an event.

Missing endpoints stay ``None``. A negative duration is preserved so clock
anomalies remain visible. Nothing here asserts a millisecond target.
"""

from dataclasses import dataclass

from btc_intelligence.domain.timestamps import EventTimestamps

_STAGES: tuple[tuple[str, str, str], ...] = (
    ("receive_to_normalize", "local_receive_timestamp_ns", "normalized_timestamp_ns"),
    ("normalize_to_aggregate", "normalized_timestamp_ns", "aggregated_timestamp_ns"),
    ("aggregate_to_signal", "aggregated_timestamp_ns", "signal_timestamp_ns"),
    ("signal_to_decision", "signal_timestamp_ns", "decision_timestamp_ns"),
    ("decision_to_execution_request", "decision_timestamp_ns", "execution_request_timestamp_ns"),
)


@dataclass(frozen=True, slots=True)
class StageMeasurement:
    name: str
    start_ns: int | None
    end_ns: int | None
    duration_ns: int | None

    @property
    def complete(self) -> bool:
        return self.duration_ns is not None


@dataclass(frozen=True, slots=True)
class LatencyDiagnostics:
    stages: tuple[StageMeasurement, ...]

    def stage(self, name: str) -> StageMeasurement:
        for item in self.stages:
            if item.name == name:
                return item
        raise KeyError(name)

    @classmethod
    def from_timestamps(cls, timestamps: EventTimestamps) -> "LatencyDiagnostics":
        if not isinstance(timestamps, EventTimestamps):
            raise TypeError("latency diagnostics require EventTimestamps")
        measured: list[StageMeasurement] = []
        for name, start_field, end_field in _STAGES:
            start_ns = getattr(timestamps, start_field)
            end_ns = getattr(timestamps, end_field)
            duration = None if start_ns is None or end_ns is None else end_ns - start_ns
            measured.append(
                StageMeasurement(name=name, start_ns=start_ns, end_ns=end_ns, duration_ns=duration)
            )
        return cls(stages=tuple(measured))
