"""Public-data TradingNode. Execution clients are not registered."""

from datetime import timedelta
import time

from nautilus_trader.adapters.binance.config import BinanceDataClientConfig
from nautilus_trader.adapters.binance.factories import BinanceLiveDataClientFactory
from nautilus_trader.adapters.bybit.config import BybitDataClientConfig
from nautilus_trader.adapters.bybit.factories import BybitLiveDataClientFactory
from nautilus_trader.common.actor import Actor
from nautilus_trader.common.config import ActorConfig, InstrumentProviderConfig, LoggingConfig
from nautilus_trader.core.nautilus_pyo3 import BybitProductType
from nautilus_trader.live.config import LiveExecEngineConfig, TradingNodeConfig
from nautilus_trader.live.node import TradingNode
from nautilus_trader.model.enums import BookType
from nautilus_trader.model.identifiers import InstrumentId, TraderId

from btc_intelligence.sensor.pipeline import SensorPipeline
from btc_intelligence.sensor.settings import VenueFeed


class MarketSensorActor(Actor):
    """Subscribe to public quotes, trades, and L2 deltas. Do not submit orders."""

    def __init__(self, pipeline: SensorPipeline, feeds: tuple[VenueFeed, ...]) -> None:
        super().__init__(ActorConfig(log_events=False, log_commands=False))
        self._pipeline = pipeline
        self._feeds = feeds

    def on_start(self) -> None:
        for feed in self._feeds:
            instrument_id = InstrumentId.from_str(f"{feed.symbol}.{feed.venue.value}")
            self.subscribe_quote_ticks(instrument_id)
            self.subscribe_trade_ticks(instrument_id)
            self.subscribe_order_book_deltas(
                instrument_id,
                book_type=BookType.L2_MBP,
                depth=feed.book_depth,
                managed=False,
            )
        self.clock.set_timer(
            "venue-health",
            timedelta(seconds=1),
            callback=self._on_health,
        )

    def on_stop(self) -> None:
        self.clock.cancel_timer("venue-health")
        self._pipeline.close()

    def on_quote_tick(self, tick) -> None:
        self._pipeline.handle(tick, local_receive_ns=time.time_ns())

    def on_trade_tick(self, tick) -> None:
        self._pipeline.handle(tick, local_receive_ns=time.time_ns())

    def on_order_book_deltas(self, deltas) -> None:
        self._pipeline.handle(deltas, local_receive_ns=time.time_ns())

    def _on_health(self, _event) -> None:
        self._pipeline.observe_clock(time.time_ns())


def build_trading_node_config(feeds: tuple[VenueFeed, ...]) -> TradingNodeConfig:
    if not feeds:
        raise ValueError("at least one public venue feed is required")
    data_clients = {}
    for feed in feeds:
        instrument_id = InstrumentId.from_str(f"{feed.symbol}.{feed.venue.value}")
        provider = InstrumentProviderConfig(load_ids=frozenset([instrument_id]))
        if feed.venue.value == "BINANCE":
            data_clients["BINANCE"] = BinanceDataClientConfig(
                api_key=None,
                api_secret=None,
                base_url_http=feed.http_base,
                base_url_ws=feed.ws_base,
                update_instruments_interval_mins=None,
                use_agg_trade_ticks=False,
                instrument_provider=provider,
            )
        elif feed.venue.value == "BYBIT":
            data_clients["BYBIT"] = BybitDataClientConfig(
                api_key=None,
                api_secret=None,
                product_types=(BybitProductType.SPOT,),
                update_instruments_interval_mins=None,
                instrument_status_poll_secs=None,
                instrument_provider=provider,
            )
        else:
            raise ValueError(f"no public data client is wired for {feed.venue.value}")
    return TradingNodeConfig(
        trader_id=TraderId("SENSOR-001"),
        logging=LoggingConfig(log_level="INFO"),
        exec_engine=LiveExecEngineConfig(reconciliation=False),
        data_clients=data_clients,
        exec_clients={},
        timeout_connection=30.0,
    )


def build_public_node(pipeline: SensorPipeline, feeds: tuple[VenueFeed, ...]) -> TradingNode:
    """Data clients only. Execution client factories are not registered."""

    node = TradingNode(config=build_trading_node_config(feeds))
    if any(feed.venue.value == "BINANCE" for feed in feeds):
        node.add_data_client_factory("BINANCE", BinanceLiveDataClientFactory)
    if any(feed.venue.value == "BYBIT" for feed in feeds):
        node.add_data_client_factory("BYBIT", BybitLiveDataClientFactory)
    node.build()
    node.trader.add_actor(MarketSensorActor(pipeline, feeds))
    return node
