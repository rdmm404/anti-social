"""When watching is off outright, whatever the budget says.

A schedule is windows separated by semicolons, each with optional days:
`mon-fri 09:00-17:00; 22:00-07:00`. A window that ends before it starts runs past
midnight and belongs to the day it starts on. Times are the server's local time.
"""

import re
from dataclasses import dataclass
from datetime import datetime, timedelta

DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
MINUTES_PER_DAY = 24 * 60
WINDOW = re.compile(r"(?:(?P<days>\S+)\s+)?(?P<start>\d{1,2}:\d{2})-(?P<end>\d{1,2}:\d{2})")


def _minutes(text: str) -> int:
    hours, minutes = map(int, text.split(":"))
    total = hours * 60 + minutes
    if minutes > 59 or total > MINUTES_PER_DAY:
        raise ValueError(f"{text} isn't a time")
    return total


def _days(text: str) -> frozenset[int]:
    found: set[int] = set()
    for part in text.split(","):
        first, _, last = part.partition("-")
        start = DAYS.index(first)
        length = (DAYS.index(last or first) - start) % 7 + 1
        found.update((start + offset) % 7 for offset in range(length))
    return frozenset(found)


@dataclass(frozen=True)
class Window:
    days: frozenset[int]
    start: int  # Minutes after midnight.
    end: int

    @classmethod
    def parse(cls, text: str) -> "Window":
        try:
            match = WINDOW.fullmatch(text)
            if not match:
                raise ValueError("wrong format")
            days = _days(match["days"]) if match["days"] else frozenset(range(7))
            start, end = _minutes(match["start"]), _minutes(match["end"])
            if start == end:
                raise ValueError("it starts when it ends")
        except ValueError as error:
            raise ValueError(
                f"Bad blocked window {text!r} ({error}). Expected something like 'mon-fri 09:00-17:00'."
            ) from error
        return cls(days, start, end)

    def end_if_active(self, now: datetime) -> datetime | None:
        midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
        for days_back in (0, 1):  # Yesterday's window may still be running.
            day = midnight - timedelta(days=days_back)
            if day.weekday() not in self.days:
                continue
            start = day + timedelta(minutes=self.start)
            end = day + timedelta(minutes=self.end + (MINUTES_PER_DAY if self.end < self.start else 0))
            if start <= now < end:
                return end
        return None


@dataclass(frozen=True)
class Schedule:
    text: str = ""
    windows: tuple[Window, ...] = ()

    @classmethod
    def parse(cls, text: str) -> "Schedule":
        """Raises on anything unreadable: a typo must not quietly mean "never blocked"."""
        normalized = " ".join(text.lower().split())
        parts = [part.strip() for part in normalized.split(";") if part.strip()]
        return cls("; ".join(parts), tuple(Window.parse(part) for part in parts))

    def blocked_until(self, now: datetime) -> datetime | None:
        """When watching reopens, following windows that run into each other. None if open now."""
        until = None
        cursor = now
        horizon = now + timedelta(days=7)  # A schedule covering the whole week never ends.
        while cursor < horizon:
            ends = [end for window in self.windows if (end := window.end_if_active(cursor))]
            if not ends:
                break
            until = cursor = max(ends)
        return until
