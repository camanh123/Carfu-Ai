"""Import the pinned NautilusTrader build and normalize one public quote."""

from decimal import Decimal

import nautilus_trader
from nautilus_trader.model.data import QuoteTick
from nautilus_trader.model.identifiers import InstrumentId, Symbol, Venue
from nautilus_trader.model.objects import Price, Quantity

from btc_intelligence.market.nautilus_source import NautilusMarketSource


def main() -> None:
    instrument = InstrumentId(Symbol("BTCUSDT"), Venue("BINANCE"))
    exchange_ns = 1_700_000_000_000_000_000
    quote = QuoteTick(
        instrument_id=instrument,
        bid_price=Price.from_str("64000.10"),
        ask_price=Price.from_str("64000.50"),
        bid_size=Quantity.from_str("1.25"),
        ask_size=Quantity.from_str("0.80"),
        ts_event=exchange_ns,
        ts_init=exchange_ns,
    )
    event = NautilusMarketSource().normalize(
        quote,
        local_receive_timestamp_ns=1,
        normalized_timestamp_ns=2,
    )
    if event.quote is None or event.quote.bid_price != Decimal("64000.10"):
        raise SystemExit("normalization failed")
    print(f"nautilus_trader {nautilus_trader.__version__} import smoke PASS")


if __name__ == "__main__":
    main()
