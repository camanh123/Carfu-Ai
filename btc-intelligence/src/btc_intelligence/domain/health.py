"""Venue health for the market sensor. Only HEALTHY venues are aggregated."""

from enum import Enum


class VenueHealth(str, Enum):
    STARTING = "STARTING"
    HEALTHY = "HEALTHY"
    STALE = "STALE"
    DESYNCED = "DESYNCED"
    DISCONNECTED = "DISCONNECTED"
    RECOVERING = "RECOVERING"
