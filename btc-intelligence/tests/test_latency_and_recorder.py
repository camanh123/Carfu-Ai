"""Latency measurement wiring and in-memory recording."""

from btc_intelligence.diagnostics.latency import LatencyDiagnostics
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.recorder.memory import MemoryRecorder
from btc_intelligence.recorder.protocol import Recorder
from tests.helpers import pipeline, quote_record


def test_stage_durations_follow_captured_timestamps() -> None:
    diagnostics = LatencyDiagnostics.from_timestamps(
        EventTimestamps(
            local_receive_timestamp_ns=1_000,
            normalized_timestamp_ns=2_000,
            aggregated_timestamp_ns=3_500,
            signal_timestamp_ns=4_000,
            decision_timestamp_ns=4_250,
            execution_request_timestamp_ns=5_000,
        )
    )
    assert diagnostics.stage("receive_to_normalize").duration_ns == 1_000
    assert diagnostics.stage("normalize_to_aggregate").duration_ns == 1_500
    assert diagnostics.stage("aggregate_to_signal").duration_ns == 500
    assert diagnostics.stage("signal_to_decision").duration_ns == 250
    assert diagnostics.stage("decision_to_execution_request").duration_ns == 750


def test_incomplete_stages_stay_unmeasured() -> None:
    diagnostics = LatencyDiagnostics.from_timestamps(
        EventTimestamps(local_receive_timestamp_ns=10, normalized_timestamp_ns=15)
    )
    assert diagnostics.stage("receive_to_normalize").duration_ns == 5
    assert diagnostics.stage("normalize_to_aggregate").duration_ns is None
    assert diagnostics.stage("decision_to_execution_request").complete is False


def test_negative_duration_is_preserved() -> None:
    diagnostics = LatencyDiagnostics.from_timestamps(
        EventTimestamps(local_receive_timestamp_ns=20, normalized_timestamp_ns=10)
    )
    assert diagnostics.stage("receive_to_normalize").duration_ns == -10


def test_pipeline_records_each_artifact_and_measures_every_stage() -> None:
    engine, recorder, _spy = pipeline()
    assert isinstance(recorder, Recorder)
    result = engine.process(quote_record())
    assert isinstance(recorder, MemoryRecorder)
    assert len(recorder.market_events) == 1
    assert len(recorder.market_states) == 1
    assert len(recorder.signals) == 1
    assert len(recorder.decisions) == 1
    assert len(recorder.intents) == 1
    assert len(recorder.executions) == 1
    names = [stage.name for stage in result.latency.stages]
    assert names == [
        "receive_to_normalize",
        "normalize_to_aggregate",
        "aggregate_to_signal",
        "signal_to_decision",
        "decision_to_execution_request",
    ]
    assert all(stage.duration_ns == 1_000 for stage in result.latency.stages)
