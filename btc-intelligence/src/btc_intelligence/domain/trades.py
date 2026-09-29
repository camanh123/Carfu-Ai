"""Trade-side vocabulary owned by BTC-Intelligence."""

from enum import Enum


class TradeSide(str, Enum):
    BUY = "BUY"
    SELL = "SELL"
    UNKNOWN = "UNKNOWN"
