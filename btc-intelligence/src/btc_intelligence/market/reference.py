"""Descriptive reference price from healthy venue mids."""

from decimal import Decimal

from btc_intelligence.domain.identifiers import ExchangeId


def median_price(values: list[Decimal]) -> Decimal | None:
    if not values:
        return None
    ordered = sorted(values)
    count = len(ordered)
    mid = count // 2
    if count % 2 == 1:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / Decimal("2")


def freshest_venue(stamps: list[tuple[ExchangeId, int]]) -> ExchangeId | None:
    if not stamps:
        return None
    return max(stamps, key=lambda item: item[1])[0]
