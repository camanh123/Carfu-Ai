"""Map Nautilus ``ts_event`` / ``ts_init`` onto project timestamp fields.

The mapping follows the installed NautilusTrader 1.231.0 adapters. It does
not copy ``ts_init`` into an exchange field. See
``docs/PHASE1_NAUTILUS_AUDIT.md``.
"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ClassifiedExchangeTime:
    exchange_timestamp_ns: int | None
    exchange_event_ns: int | None
    exchange_transaction_ns: int | None
    nautilus_substituted_local_clock: bool


def classify_exchange_time(
    *,
    venue: str,
    kind: str,
    ts_event_ns: int,
    ts_init_ns: int,
) -> ClassifiedExchangeTime:
    """Classify one Nautilus data timestamp pair.

    ``kind`` is ``QUOTE``, ``TRADE``, or ``BOOK``.
    """

    venue_name = venue.upper()
    same_clock = ts_event_ns == ts_init_ns
    if kind == "TRADE":
        # Binance spot uses trade time T and drops event time E.
        # Bybit uses public trade field T and drops the envelope ts.
        return ClassifiedExchangeTime(
            exchange_timestamp_ns=ts_event_ns,
            exchange_event_ns=None,
            exchange_transaction_ns=ts_event_ns,
            nautilus_substituted_local_clock=False,
        )
    if venue_name == "BINANCE" and same_clock and kind in {"QUOTE", "BOOK"}:
        # Spot bookTicker without T, and partial-depth snapshots, set
        # ts_event from ts_init inside the adapter.
        return ClassifiedExchangeTime(
            exchange_timestamp_ns=None,
            exchange_event_ns=None,
            exchange_transaction_ns=None,
            nautilus_substituted_local_clock=True,
        )
    return ClassifiedExchangeTime(
        exchange_timestamp_ns=ts_event_ns,
        exchange_event_ns=ts_event_ns,
        exchange_transaction_ns=None,
        nautilus_substituted_local_clock=False,
    )
