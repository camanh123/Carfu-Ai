"""Execution port contracts."""

from decimal import Decimal

from btc_intelligence.domain.decisions import Decision
from btc_intelligence.domain.execution import ExecutionStatus
from btc_intelligence.execution.disabled import DisabledExecutionPort
from btc_intelligence.execution.nautilus_port import NautilusExecutionPort
from btc_intelligence.execution.port import ExecutionPort
from tests.helpers import intent, pipeline, quote_record


def test_ports_satisfy_the_execution_protocol() -> None:
    assert isinstance(DisabledExecutionPort(), ExecutionPort)
    assert isinstance(NautilusExecutionPort(), ExecutionPort)


def test_disabled_port_does_not_submit() -> None:
    result = DisabledExecutionPort().submit(
        intent(Decision.BUY, quantity=Decimal("0.01")),
        execution_request_timestamp_ns=80,
    )
    assert result.status is ExecutionStatus.REJECTED
    assert result.reason == "live_trading_disabled"
    assert result.submitted_to_venue is False
    assert result.timestamps.execution_request_timestamp_ns == 80


def test_nautilus_port_refuses_without_building_an_order() -> None:
    result = NautilusExecutionPort().submit(
        intent(Decision.SELL, quantity=Decimal("0.02")),
        execution_request_timestamp_ns=90,
    )
    assert result.status is ExecutionStatus.REJECTED
    assert result.reason == "nautilus_execution_disabled"
    assert result.submitted_to_venue is False


def test_pipeline_does_not_call_the_port_for_wait() -> None:
    engine, recorder, spy = pipeline()
    result = engine.process(quote_record())
    assert spy.calls == []
    assert result.execution_port_called is False
    assert result.decision.decision is Decision.WAIT
    assert result.risk.allowed is True
    assert result.execution.status is ExecutionStatus.NOT_SENT
    assert result.execution.reason == "wait_no_order"
    assert result.execution.submitted_to_venue is False
    assert engine.execution_port is spy
    assert len(recorder.executions) == 1
