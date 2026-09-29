"""Execution results owned by BTC-Intelligence, not by an exchange client."""

from dataclasses import dataclass
from enum import Enum

from btc_intelligence.domain.timestamps import EventTimestamps


class ExecutionStatus(str, Enum):
    NOT_SENT = "NOT_SENT"
    REJECTED = "REJECTED"


@dataclass(frozen=True, slots=True)
class ExecutionResult:
    status: ExecutionStatus
    reason: str
    submitted_to_venue: bool
    timestamps: EventTimestamps

    def __post_init__(self) -> None:
        if not isinstance(self.status, ExecutionStatus):
            raise ValueError("status must be an ExecutionStatus")
        if not isinstance(self.reason, str) or not self.reason:
            raise ValueError("reason must be a non-empty string")
        if not isinstance(self.submitted_to_venue, bool):
            raise ValueError("submitted_to_venue must be bool")
        if self.submitted_to_venue:
            raise ValueError("Phase 0 forbids venue submission")
        if not isinstance(self.timestamps, EventTimestamps):
            raise ValueError("timestamps must be EventTimestamps")
