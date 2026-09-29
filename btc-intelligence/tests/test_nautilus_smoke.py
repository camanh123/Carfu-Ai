"""Prove the pinned NautilusTrader package imports and its public data API is usable."""

import json
from decimal import Decimal
from pathlib import Path

import nautilus_trader
from nautilus_trader.model.book import OrderBook
from nautilus_trader.model.data import QuoteTick, TradeTick
from nautilus_trader.model.enums import AggressorSide, BookType
from nautilus_trader.model.identifiers import InstrumentId, Symbol, TradeId, Venue
from nautilus_trader.model.objects import Price, Quantity
from nautilus_trader.trading.strategy import Strategy

from btc_intelligence.domain.events import MarketEventKind
from btc_intelligence.domain.identifiers import ExchangeId
from btc_intelligence.domain.trades import TradeSide
from btc_intelligence.market.nautilus_source import NautilusMarketSource

PINNED_VERSION = "1.231.0"


def test_pinned_package_imports() -> None:
    assert nautilus_trader.__version__ == PINNED_VERSION
    assert callable(Strategy.submit_order)
    assert callable(Strategy.on_quote_tick)
    assert callable(Strategy.on_trade_tick)
    recorded = json.loads((Path(__file__).resolve().parents[1] / "config" / "environment.json").read_text(encoding="utf-8"))
    assert recorded["nautilus_version"] == PINNED_VERSION
    assert recorded["live_trading"] is False


def test_public_quote_trade_and_book_normalize_without_a_live_client() -> None:
    instrument = InstrumentId(Symbol("BTCUSDT"), Venue("BINANCE"))
    exchange_ns = 1_700_000_000_000_000_000
    init_ns = exchange_ns + 25
    quote = QuoteTick(
        instrument_id=instrument,
        bid_price=Price.from_str("64000.10"),
        ask_price=Price.from_str("64000.50"),
        bid_size=Quantity.from_str("1.25"),
        ask_size=Quantity.from_str("0.80"),
        ts_event=exchange_ns,
        ts_init=init_ns,
    )
    trade = TradeTick(
        instrument_id=instrument,
        price=Price.from_str("64000.20"),
        size=Quantity.from_str("0.010"),
        aggressor_side=AggressorSide.SELLER,
        trade_id=TradeId("smoke-1"),
        ts_event=exchange_ns + 5,
        ts_init=init_ns,
    )
    book = OrderBook(instrument_id=instrument, book_type=BookType.L1_MBP)
    book.update_quote_tick(quote)

    source = NautilusMarketSource()
    quote_event = source.normalize(quote, local_receive_timestamp_ns=100, normalized_timestamp_ns=110)
    trade_event = source.normalize(trade, local_receive_timestamp_ns=120, normalized_timestamp_ns=130)
    book_event = source.normalize(book, local_receive_timestamp_ns=140, normalized_timestamp_ns=150)

    assert quote_event.kind is MarketEventKind.QUOTE
    assert quote_event.instrument.venue is ExchangeId.BINANCE
    assert quote_event.quote is not None
    assert quote_event.quote.bid_price == Decimal("64000.10")
    assert quote_event.quote.ask_size == Decimal("0.80")
    assert quote_event.timestamps.exchange_timestamp_ns == exchange_ns
    assert quote_event.timestamps.local_receive_timestamp_ns == 100
    assert quote_event.timestamps.source_init_timestamp_ns == init_ns

    assert trade_event.trade is not None
    assert trade_event.trade.side is TradeSide.SELL
    assert trade_event.trade.price == Decimal("64000.20")
    assert trade_event.trade.size == Decimal("0.010")

    assert book_event.book is not None
    assert book_event.book.book_type == "L1_MBP"
    assert book_event.book.bids[0].price == Decimal("64000.10")
    assert book_event.book.asks[0].size == Decimal("0.80")
    assert book_event.timestamps.normalized_timestamp_ns == 150
