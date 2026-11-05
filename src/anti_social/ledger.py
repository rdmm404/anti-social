"""The ledger decides how much watching is allowed. It knows nothing about YouTube.

Time is charged for media the server delivers, not for what a client reports:
- screen-off playback costs the same as watching, because segments still flow;
- each second of a video is charged once per budget day, so audio and video
  tracks, quality switches, reloads and a second device never double-charge.

An unlock tops up the day's budget instead of starting a countdown, so pausing
doesn't waste it and a faster playback speed doesn't stretch it.

Any later platform can spend from the same budget by calling `charge`.
"""

import json
import sqlite3
import time
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from .config import Config

Interval = list[float]

# setting -> (minimum, maximum, whether a higher value is looser)
SETTINGS: dict[str, tuple[int, int, bool]] = {
    "daily_minutes": (0, 1440, True),
    "unlocks_per_day": (0, 10, True),
    "unlock_minutes": (1, 240, True),
    "unlock_wait_seconds": (0, 3600, False),
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS pending_changes (
    id INTEGER PRIMARY KEY,
    key TEXT NOT NULL,
    value INTEGER NOT NULL,
    requested_at REAL NOT NULL,
    apply_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS watched (
    day TEXT NOT NULL,
    video_id TEXT NOT NULL,
    title TEXT NOT NULL,
    channel TEXT NOT NULL,
    intervals TEXT NOT NULL,
    charged_seconds REAL NOT NULL DEFAULT 0,
    unlocked_seconds REAL NOT NULL DEFAULT 0,
    last_at REAL NOT NULL,
    PRIMARY KEY (day, video_id)
);
CREATE TABLE IF NOT EXISTS unlocks (
    id INTEGER PRIMARY KEY,
    day TEXT NOT NULL,
    starts_at REAL NOT NULL,
    seconds REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS subscriptions (
    channel_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    added_at REAL NOT NULL
);
"""


class LedgerError(Exception):
    """A request the rules refuse. The message is safe to show to the user."""


def add_interval(intervals: list[Interval], start: float, end: float) -> tuple[float, list[Interval]]:
    """Merge [start, end) into sorted intervals; return (newly covered seconds, merged)."""
    if end <= start:
        return 0.0, intervals
    merged: list[Interval] = []
    lo, hi = start, end
    overlap = 0.0
    placed = False
    for a, b in intervals:
        if b < lo:
            merged.append([a, b])
        elif a > hi:
            if not placed:
                merged.append([lo, hi])
                placed = True
            merged.append([a, b])
        else:
            overlap += max(0.0, min(b, end) - max(a, start))
            lo, hi = min(lo, a), max(hi, b)
    if not placed:
        merged.append([lo, hi])
    return (end - start) - overlap, merged


class Ledger:
    def __init__(self, config: Config, clock: Callable[[], float] = time.time):
        self._config = config
        self._clock = clock
        Path(config.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(config.db_path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._migrate_unlocks()
        seeds = {
            "daily_minutes": config.seed_daily_minutes,
            "unlocks_per_day": config.seed_unlocks_per_day,
            "unlock_minutes": config.seed_unlock_minutes,
            "unlock_wait_seconds": config.seed_unlock_wait_seconds,
        }
        with self._db:
            self._db.executemany(
                "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", seeds.items()
            )

    def _migrate_unlocks(self) -> None:
        """Unlocks used to be a time window. The window's length is the top-up it was worth."""
        columns = {r["name"] for r in self._db.execute("PRAGMA table_info(unlocks)")}
        if "ends_at" not in columns:
            return
        with self._db:
            self._db.execute("ALTER TABLE unlocks ADD COLUMN seconds REAL NOT NULL DEFAULT 0")
            self._db.execute("UPDATE unlocks SET seconds = ends_at - starts_at")
            self._db.execute("ALTER TABLE unlocks DROP COLUMN ends_at")

    # ---- budget day ----

    def _day(self, ts: float | None = None) -> str:
        ts = self._clock() if ts is None else ts
        shifted = datetime.fromtimestamp(ts) - timedelta(hours=self._config.day_start_hour)
        return shifted.date().isoformat()

    def _resets_at(self) -> float:
        today = datetime.fromisoformat(self._day())
        return (today + timedelta(days=1, hours=self._config.day_start_hour)).timestamp()

    # ---- blocked hours ----

    def blocked_until(self) -> float | None:
        """Overrides everything: remaining budget, unlocks and media already paid for."""
        until = self._config.blocked.blocked_until(datetime.fromtimestamp(self._clock()))
        return until.timestamp() if until else None

    # ---- settings ----

    def _apply_due_changes(self) -> None:
        now = self._clock()
        due = self._db.execute(
            "SELECT id, key, value FROM pending_changes WHERE apply_at <= ?", (now,)
        ).fetchall()
        if not due:
            return
        with self._db:
            for row in due:
                self._db.execute(
                    "UPDATE settings SET value = ? WHERE key = ?", (row["value"], row["key"])
                )
                self._db.execute("DELETE FROM pending_changes WHERE id = ?", (row["id"],))

    def settings(self) -> dict[str, int]:
        self._apply_due_changes()
        rows = self._db.execute("SELECT key, value FROM settings").fetchall()
        return {r["key"]: r["value"] for r in rows}

    def pending_changes(self) -> list[dict[str, Any]]:
        self._apply_due_changes()
        rows = self._db.execute(
            "SELECT id, key, value, apply_at FROM pending_changes ORDER BY apply_at"
        ).fetchall()
        return [dict(r) for r in rows]

    def change_settings(self, changes: dict[str, int]) -> dict[str, list[str]]:
        """Tightening applies now. Loosening waits out the cooling-off period.

        Any new value for a setting replaces a queued one, so re-requesting a
        loosening restarts its clock instead of stacking up.
        """
        for key, value in changes.items():
            if key not in SETTINGS:
                raise LedgerError(f"Unknown setting: {key}")
            low, high, _ = SETTINGS[key]
            if not low <= value <= high:
                raise LedgerError(f"{key} must be between {low} and {high}")

        current = self.settings()
        now = self._clock()
        applied: list[str] = []
        queued: list[str] = []
        with self._db:
            for key, value in changes.items():
                pending = self._db.execute(
                    "SELECT value FROM pending_changes WHERE key = ?", (key,)
                ).fetchone()
                if pending and pending["value"] == value:
                    continue  # Saving the form again must not restart the clock.
                self._db.execute("DELETE FROM pending_changes WHERE key = ?", (key,))
                if value == current[key]:
                    continue
                higher_is_looser = SETTINGS[key][2]
                loosens = value > current[key] if higher_is_looser else value < current[key]
                if loosens:
                    self._db.execute(
                        "INSERT INTO pending_changes (key, value, requested_at, apply_at)"
                        " VALUES (?, ?, ?, ?)",
                        (key, value, now, now + self._config.cooling_off_hours * 3600),
                    )
                    queued.append(key)
                else:
                    self._db.execute(
                        "UPDATE settings SET value = ? WHERE key = ?", (value, key)
                    )
                    applied.append(key)
        return {"applied": applied, "queued": queued}

    def cancel_change(self, change_id: int) -> None:
        with self._db:
            self._db.execute("DELETE FROM pending_changes WHERE id = ?", (change_id,))

    # ---- unlocks ----

    def _waiting_unlock_start(self) -> float | None:
        row = self._db.execute(
            "SELECT MAX(starts_at) AS starts_at FROM unlocks WHERE starts_at > ?",
            (self._clock(),),
        ).fetchone()
        starts_at: float | None = row["starts_at"]
        return starts_at

    def _budget_seconds(self) -> float:
        """The daily budget plus every unlock that has finished its wait."""
        row = self._db.execute(
            "SELECT COALESCE(SUM(seconds), 0) AS added FROM unlocks WHERE day = ? AND starts_at <= ?",
            (self._day(), self._clock()),
        ).fetchone()
        return self.settings()["daily_minutes"] * 60 + float(row["added"])

    def _unlocks_used(self) -> int:
        row = self._db.execute(
            "SELECT COUNT(*) AS n FROM unlocks WHERE day = ?", (self._day(),)
        ).fetchone()
        return int(row["n"])

    def start_unlock(self) -> dict[str, Any]:
        settings = self.settings()
        if self.blocked_until():
            raise LedgerError("Unlocks don't apply during blocked hours.")
        if self._waiting_unlock_start():
            raise LedgerError("An unlock is already on its way.")
        if self._used_seconds() < self._budget_seconds():
            raise LedgerError("Unlocks open once today's budget runs out.")
        if self._unlocks_used() >= settings["unlocks_per_day"]:
            raise LedgerError("No unlocks left today.")
        with self._db:
            self._db.execute(
                "INSERT INTO unlocks (day, starts_at, seconds) VALUES (?, ?, ?)",
                (
                    self._day(),
                    self._clock() + settings["unlock_wait_seconds"],
                    settings["unlock_minutes"] * 60,
                ),
            )
        return self.status()

    # ---- charging ----

    def _used_seconds(self, column: str = "charged_seconds + unlocked_seconds") -> float:
        row = self._db.execute(
            f"SELECT COALESCE(SUM({column}), 0) AS used FROM watched WHERE day = ?",
            (self._day(),),
        ).fetchone()
        return float(row["used"])

    def _intervals(self, video_id: str) -> list[Interval]:
        row = self._db.execute(
            "SELECT intervals FROM watched WHERE day = ? AND video_id = ?",
            (self._day(), video_id),
        ).fetchone()
        if not row:
            return []
        intervals: list[Interval] = json.loads(row["intervals"])
        return intervals

    def _has_time(self) -> bool:
        return self._used_seconds() < self._budget_seconds()

    def can_watch(self) -> bool:
        return not self.blocked_until() and self._has_time()

    def may_deliver(self, video_id: str, start: float, end: float) -> bool:
        """Media already paid for today stays available after time runs out, but not when blocked."""
        if self.blocked_until():
            return False
        if self._has_time():
            return True
        new, _ = add_interval(self._intervals(video_id), start, end)
        return new <= 0

    def charge(self, video_id: str, start: float, end: float, title: str, channel: str) -> float:
        """Record delivered media and return the seconds that were new today."""
        new, merged = add_interval(self._intervals(video_id), start, end)
        if new <= 0:
            return 0.0
        # The daily budget pays first. Whatever it can't cover came from an unlock.
        daily_left = self.settings()["daily_minutes"] * 60 - self._used_seconds("charged_seconds")
        charged = min(new, max(0.0, daily_left))
        with self._db:
            self._db.execute(
                "INSERT INTO watched"
                " (day, video_id, title, channel, intervals, charged_seconds, unlocked_seconds, last_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                " ON CONFLICT (day, video_id) DO UPDATE SET"
                " intervals = excluded.intervals,"
                " charged_seconds = charged_seconds + excluded.charged_seconds,"
                " unlocked_seconds = unlocked_seconds + excluded.unlocked_seconds,"
                " last_at = excluded.last_at",
                (
                    self._day(),
                    video_id,
                    title,
                    channel,
                    json.dumps(merged),
                    charged,
                    new - charged,
                    self._clock(),
                ),
            )
        return new

    # ---- reading state ----

    def status(self) -> dict[str, Any]:
        settings = self.settings()
        budget = self._budget_seconds()
        used = self._used_seconds()
        blocked_until = self.blocked_until()
        return {
            "budget_seconds": budget,
            "used_seconds": round(used),
            "remaining_seconds": max(0, round(budget - used)),
            "can_watch": used < budget and not blocked_until,
            "blocked_until": blocked_until,
            "resets_at": self._resets_at(),
            "unlocks_left": max(0, settings["unlocks_per_day"] - self._unlocks_used()),
            "budget_spent": used >= budget,
            "unlock_minutes": settings["unlock_minutes"],
            "unlock_wait_seconds": settings["unlock_wait_seconds"],
            "unlock_starts_at": self._waiting_unlock_start(),
            "now": self._clock(),
        }

    def history(self, days: int = 7) -> dict[str, Any]:
        today = datetime.fromisoformat(self._day())
        totals = []
        for offset in range(days):
            day = (today - timedelta(days=offset)).date().isoformat()
            row = self._db.execute(
                "SELECT COALESCE(SUM(charged_seconds), 0) AS charged,"
                " COALESCE(SUM(unlocked_seconds), 0) AS unlocked FROM watched WHERE day = ?",
                (day,),
            ).fetchone()
            totals.append(
                {"day": day, "charged_seconds": round(row["charged"]), "unlocked_seconds": round(row["unlocked"])}
            )
        videos = self._db.execute(
            "SELECT video_id, title, channel, charged_seconds, unlocked_seconds FROM watched"
            " WHERE day = ? ORDER BY last_at DESC",
            (self._day(),),
        ).fetchall()
        return {"days": totals, "today": [dict(v) for v in videos]}

    def search_watched(
        self,
        query: str = "",
        start_day: str | None = None,
        end_day: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """Newest first. Days are budget days, and a video appears once per day watched."""
        where = ["1 = 1"]
        params: list[Any] = []
        if query.strip():
            escaped = query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            where.append("(title LIKE ? ESCAPE '\\' OR channel LIKE ? ESCAPE '\\')")
            params += [f"%{escaped}%", f"%{escaped}%"]
        if start_day:
            where.append("day >= ?")
            params.append(start_day)
        if end_day:
            where.append("day <= ?")
            params.append(end_day)
        rows = self._db.execute(
            "SELECT day, video_id, title, channel, charged_seconds, unlocked_seconds FROM watched"
            f" WHERE {' AND '.join(where)} ORDER BY day DESC, last_at DESC LIMIT ? OFFSET ?",
            (*params, limit, offset),
        ).fetchall()
        return [dict(r) for r in rows]

    # ---- subscriptions ----

    def subscriptions(self) -> list[dict[str, Any]]:
        rows = self._db.execute(
            "SELECT channel_id, name FROM subscriptions ORDER BY name COLLATE NOCASE"
        ).fetchall()
        return [dict(r) for r in rows]

    def subscribe(self, channel_id: str, name: str) -> None:
        with self._db:
            self._db.execute(
                "INSERT OR REPLACE INTO subscriptions (channel_id, name, added_at) VALUES (?, ?, ?)",
                (channel_id, name, self._clock()),
            )

    def unsubscribe(self, channel_id: str) -> None:
        with self._db:
            self._db.execute("DELETE FROM subscriptions WHERE channel_id = ?", (channel_id,))
