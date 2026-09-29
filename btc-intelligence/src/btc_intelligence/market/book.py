"""Local L2 book. A failed integrity check marks the book untrusted."""

from dataclasses import dataclass
from decimal import Decimal

from btc_intelligence.domain.events import BookDelta, BookDeltaEvent, BookLevel, BookSide, BookUpdateAction


@dataclass(frozen=True, slots=True)
class BookApplyResult:
    trusted: bool
    crossed: bool
    duplicate: bool
    out_of_order: bool
    invalid: bool
    snapshot: bool


class L2Book:
    """Price-to-size book for one venue.

    Forward sequence jumps are applied. Binance diff packets expose only the
    final update id through Nautilus, so a jump is not proof of a gap.
    A sequence moving backwards is treated as loss of synchronization.
    """

    def __init__(self, *, publish_depth: int = 50) -> None:
        if publish_depth <= 0:
            raise ValueError("publish_depth must be > 0")
        self.publish_depth = publish_depth
        self._bids: dict[Decimal, Decimal] = {}
        self._asks: dict[Decimal, Decimal] = {}
        self.sequence: int | None = None
        self.trusted = False
        self.synchronized = False

    def clear(self) -> None:
        self._bids.clear()
        self._asks.clear()
        self.sequence = None
        self.trusted = False
        self.synchronized = False

    def apply(self, event: BookDeltaEvent) -> BookApplyResult:
        if event.is_snapshot:
            self._bids.clear()
            self._asks.clear()
            self.sequence = None
            self.trusted = False
            self.synchronized = False
        duplicate = False
        out_of_order = False
        if (
            event.sequence is not None
            and self.sequence is not None
            and not event.is_snapshot
        ):
            if event.sequence < self.sequence:
                out_of_order = True
                self.trusted = False
                self.synchronized = False
                return BookApplyResult(False, False, False, True, False, event.is_snapshot)
            if event.sequence == self.sequence:
                duplicate = True
                return BookApplyResult(self.trusted, False, True, False, False, False)
        try:
            self._apply_levels(event.deltas)
        except ValueError:
            self.trusted = False
            self.synchronized = False
            return BookApplyResult(False, False, False, False, True, event.is_snapshot)
        if event.sequence is not None:
            self.sequence = event.sequence
        crossed = self._crossed()
        if crossed:
            self.trusted = False
            self.synchronized = False
            return BookApplyResult(False, True, duplicate, False, False, event.is_snapshot)
        complete = self.best_bid() is not None and self.best_ask() is not None
        self.synchronized = complete
        self.trusted = complete
        return BookApplyResult(self.trusted, False, duplicate, False, False, event.is_snapshot)

    def best_bid(self) -> tuple[Decimal, Decimal] | None:
        if not self._bids:
            return None
        price = max(self._bids)
        return price, self._bids[price]

    def best_ask(self) -> tuple[Decimal, Decimal] | None:
        if not self._asks:
            return None
        price = min(self._asks)
        return price, self._asks[price]

    def top_bids(self, depth: int | None = None) -> tuple[BookLevel, ...]:
        limit = self.publish_depth if depth is None else depth
        prices = sorted(self._bids, reverse=True)[:limit]
        return tuple(BookLevel(price, self._bids[price]) for price in prices)

    def top_asks(self, depth: int | None = None) -> tuple[BookLevel, ...]:
        limit = self.publish_depth if depth is None else depth
        prices = sorted(self._asks)[:limit]
        return tuple(BookLevel(price, self._asks[price]) for price in prices)

    def _apply_levels(self, deltas: tuple[BookDelta, ...]) -> None:
        for delta in deltas:
            if delta.action is BookUpdateAction.CLEAR:
                self._bids.clear()
                self._asks.clear()
                continue
            if delta.side is None or delta.price is None or delta.size is None:
                raise ValueError("incomplete book delta")
            side = self._bids if delta.side is BookSide.BID else self._asks
            if delta.action is BookUpdateAction.DELETE or delta.size == 0:
                side.pop(delta.price, None)
                continue
            if delta.action not in (BookUpdateAction.ADD, BookUpdateAction.UPDATE):
                raise ValueError(f"unsupported book action {delta.action}")
            side[delta.price] = delta.size

    def _crossed(self) -> bool:
        bid = self.best_bid()
        ask = self.best_ask()
        if bid is None or ask is None:
            return False
        return bid[0] >= ask[0]
