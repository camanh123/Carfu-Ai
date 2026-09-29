"""Shared fixtures for contract tests."""

from decimal import Decimal

from btc_intelligence.decision.engine import DecisionEngine
from btc_intelligence.domain.decisions import Decision, TradeIntent
from btc_intelligence.domain.events import NormalizedMarketEvent
from btc_intelligence.domain.execution import ExecutionResult
from btc_intelligence.domain.identifiers import ExchangeId, InstrumentRef
from btc_intelligence.domain.timestamps import EventTimestamps
from btc_intelligence.execution.port import ExecutionPort
from btc_intelligence.market.aggregator import MarketAggregator
from btc_intelligence.market.records import RawBookRecord, RawLevel, RawQuoteRecord, RawTradeRecord
from btc_intelligence.market.source import NeutralMarketSource
from btc_intelligence.pipeline import IntelligencePipeline, SequenceClock
from btc_intelligence.recorder.memory import MemoryRecorder
from btc_intelligence.risk.guard import RiskGuard
from btc_intelligence.signals.engine import SignalEngine

BTC = InstrumentRef(symbol="BTCUSDT", venue=ExchangeId.BINANCE)


def quote_record(
    *,
    venue: str = "BINANCE",
    bid: str = "64000.10",
    ask: str = "64000.50",
    bid_size: str = "1.25",
    ask_size: str = "0.80",
    exchange_timestamp_ns: int = 1_700_000_000_000_000_000,
) -> RawQuoteRecord:
    return RawQuoteRecord(
        symbol="BTCUSDT",
        venue=venue,
        bid_price=bid,
        ask_price=ask,
        bid_size=bid_size,
        ask_size=ask_size,
        exchange_timestamp_ns=exchange_timestamp_ns,
    )


def trade_record(*, side: str = "BUY", price: str = "64000.20", size: str = "0.01") -> RawTradeRecord:
    return RawTradeRecord(
        symbol="BTCUSDT",
        venue="BINANCE",
        price=price,
        size=size,
        side=side,
        trade_id="trade-1",
        exchange_timestamp_ns=1_700_000_000_000_000_100,
    )


def book_record() -> RawBookRecord:
    return RawBookRecord(
        symbol="BTCUSDT",
        venue="BYBIT",
        bids=(RawLevel("64000.00", "2"), RawLevel("63999.50", "4")),
        asks=(RawLevel("64001.00", "3"),),
        book_type="L2_MBP",
        sequence=7,
        exchange_timestamp_ns=1_700_000_000_000_000_200,
    )


def normalize_quote(record: RawQuoteRecord | None = None) -> NormalizedMarketEvent:
    return NeutralMarketSource().normalize(
        record or quote_record(),
        local_receive_timestamp_ns=10,
        normalized_timestamp_ns=20,
    )


def intent(
    decision: Decision,
    *,
    quantity: Decimal | None = None,
    live_trading: bool = False,
    leverage: Decimal | None = None,
    withdraw: bool = False,
) -> TradeIntent:
    return TradeIntent(
        decision=decision,
        instrument=BTC,
        quantity=quantity,
        reason="test",
        live_trading=live_trading,
        leverage=leverage,
        withdraw=withdraw,
        timestamps=EventTimestamps(decision_timestamp_ns=50),
    )


class SpyExecutionPort:
    def __init__(self) -> None:
        self.calls: list[TradeIntent] = []

    def submit(
        self,
        submitted: TradeIntent,
        *,
        execution_request_timestamp_ns: int,
    ) -> ExecutionResult:
        self.calls.append(submitted)
        raise AssertionError("Phase 0 pipeline must not call the execution port")


def pipeline(port: ExecutionPort | None = None) -> tuple[IntelligencePipeline, MemoryRecorder, SpyExecutionPort]:
    spy = port if isinstance(port, SpyExecutionPort) else SpyExecutionPort()
    recorder = MemoryRecorder()
    engine = IntelligencePipeline(
        source=NeutralMarketSource(),
        aggregator=MarketAggregator(BTC),
        signals=SignalEngine(),
        decisions=DecisionEngine(),
        risk=RiskGuard(),
        execution=spy,
        recorder=recorder,
        clock=SequenceClock(start_ns=1_000, step_ns=1_000),
    )
    return engine, recorder, spy
