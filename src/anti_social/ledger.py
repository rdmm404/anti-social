"""The ledger decides how much watching is allowed. It knows nothing about YouTube.

Time is charged for media the server delivers, not for what a client reports:
- screen-off playback costs the same as watching, because segments still flow;
- each second of a video is charged once per budget day, so audio and video
  tracks, quality switches, reloads and a second device never double-charge.

Watching happens in sessions. A session is a number of videos chosen before
browsing, because the decision is easier to keep when it's made before anything
tempting is on screen. A video takes up a slot once it has been delivered for
SAMPLE_SECONDS, so backing out of a wrong click is free, a few times per session.
The next session opens a while after the last delivery, which is what stops one
session running straight into another.

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
    "session_gap_minutes": (0, 1440, False),
}

MAX_SESSION_VIDEOS = 3
SAMPLES_PER_SESSION = 3
# Measured in wall-clock time from first to last delivery, because delivery runs ahead of
# the playhead by a buffer: counting delivered seconds would charge for what wasn't seen.
SAMPLE_SECONDS = 30
# Longer than this with nothing delivered and the session's unused videos are gone, so
# a session opened at lunch can't skip the question in the evening.
SESSION_IDLE_SECONDS = 30 * 60

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
CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY,
    day TEXT NOT NULL,
    videos INTEGER NOT NULL,
    started_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS session_videos (
    session_id INTEGER NOT NULL,
    video_id TEXT NOT NULL,
    first_at REAL NOT NULL,
    last_at REAL NOT NULL,
    PRIMARY KEY (session_id, video_id)
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
        self._retire_unlocks()
        seeds = {
            "daily_minutes": config.seed_daily_minutes,
            "session_gap_minutes": config.seed_session_gap_minutes,
        }
        with self._db:
            self._db.executemany(
                "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", seeds.items()
            )

    def _retire_unlocks(self) -> None:
        """Runs once, on a database from before sessions.

        The cap moves to its new seed here rather than through cooling-off: it replaces
        the unlocks being removed, so it isn't a loosening made in the moment.
        Time already watched on unlocks stays in `watched.unlocked_seconds`.
        """
        exists = self._db.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'unlocks'"
        ).fetchone()
        if not exists:
            return
        known = ",".join("?" * len(SETTINGS))
        with self._db:
            self._db.execute("DROP TABLE unlocks")
            self._db.execute(f"DELETE FROM settings WHERE key NOT IN ({known})", tuple(SETTINGS))
            self._db.execute(
                f"DELETE FROM pending_changes WHERE key NOT IN ({known})", tuple(SETTINGS)
            )
            seed = self._config.seed_daily_minutes
            self._db.execute(
                "UPDATE settings SET value = ? WHERE key = 'daily_minutes' AND value < ?",
                (seed, seed),
            )

    # ---- budget day ----

    def _day(self, ts: float | None = None) -> str:
        ts = self._clock() if ts is None else ts
        shifted = datetime.fromtimestamp(ts) - timedelta(hours=self._config.day_start_hour)
        return shifted.date().isoformat()

    def _resets_at(self) -> float:
        today = datetime.fromisoformat(self._day())
        return (today + timedelta(days=1, hours=self._config.day_start_hour)).timestamp()

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

    # ---- sessions ----

    def _current_session(self) -> sqlite3.Row | None:
        """Today's latest session, unless nothing has been delivered for too long."""
        session: sqlite3.Row = self._db.execute(
            "SELECT s.id, s.videos, MAX(s.started_at, COALESCE(MAX(v.last_at), 0)) AS active_at"
            " FROM sessions s LEFT JOIN session_videos v ON v.session_id = s.id"
            " WHERE s.id = (SELECT MAX(id) FROM sessions WHERE day = ?)",
            (self._day(),),
        ).fetchone()
        if session["id"] is None or self._clock() - session["active_at"] >= SESSION_IDLE_SECONDS:
            return None
        return session

    def _session_counts(self, session_id: int) -> tuple[int, int]:
        """(videos that have taken up a slot, videos still under the sample limit)."""
        row = self._db.execute(
            "SELECT COALESCE(SUM(last_at - first_at >= ?), 0) AS kept,"
            " COALESCE(SUM(last_at - first_at < ?), 0) AS sampled"
            " FROM session_videos WHERE session_id = ?",
            (SAMPLE_SECONDS, SAMPLE_SECONDS, session_id),
        ).fetchone()
        return int(row["kept"]), int(row["sampled"])

    @staticmethod
    def _slots_used(kept: int, sampled: int) -> int:
        """Once the free samples are gone, a video takes up a slot as soon as it starts."""
        return kept + max(0, sampled - SAMPLES_PER_SESSION)

    def _session_has_room(self, session: sqlite3.Row) -> bool:
        return self._slots_used(*self._session_counts(session["id"])) < int(session["videos"])

    def _kept_in(self, video_id: str) -> int | None:
        """The session in which this video took up a slot today, if it has."""
        row = self._db.execute(
            "SELECT v.session_id FROM session_videos v JOIN sessions s ON s.id = v.session_id"
            " WHERE s.day = ? AND v.video_id = ? AND v.last_at - v.first_at >= ?"
            " ORDER BY v.session_id DESC LIMIT 1",
            (self._day(), video_id, SAMPLE_SECONDS),
        ).fetchone()
        return int(row["session_id"]) if row else None

    def _next_session_at(self) -> float | None:
        row = self._db.execute("SELECT MAX(last_at) AS last_at FROM session_videos").fetchone()
        if row["last_at"] is None:
            return None
        return float(row["last_at"]) + self.settings()["session_gap_minutes"] * 60

    def _state(self) -> str:
        if not self.can_watch():
            return "no_time"
        session = self._current_session()
        if session and self._session_has_room(session):
            return "open"
        next_at = self._next_session_at()
        return "gap" if next_at and self._clock() < next_at else "choose"

    def start_session(self, videos: int) -> dict[str, Any]:
        if not 1 <= videos <= MAX_SESSION_VIDEOS:
            raise LedgerError(f"A session is 1 to {MAX_SESSION_VIDEOS} videos.")
        state = self._state()
        if state == "no_time":
            raise LedgerError("No time left today.")
        if state == "open":
            raise LedgerError("A session is already open.")
        if state == "gap":
            raise LedgerError("The next session isn't open yet.")
        with self._db:
            self._db.execute(
                "INSERT INTO sessions (day, videos, started_at) VALUES (?, ?, ?)",
                (self._day(), videos, self._clock()),
            )
        return self.status()

    def can_browse(self) -> bool:
        return self._state() == "open"

    def may_open(self, video_id: str) -> bool:
        """Whether this video can be played from wherever it has got to."""
        if not self.can_watch():
            return False
        # A video that's been kept today resumes without a new slot, so pausing is free.
        if self._kept_in(video_id) is not None:
            return True
        session = self._current_session()
        if session is None:
            return False
        row = self._db.execute(
            "SELECT first_at FROM session_videos WHERE session_id = ? AND video_id = ?",
            (session["id"], video_id),
        ).fetchone()
        if row is None:
            return self._session_has_room(session)
        if self._clock() - row["first_at"] < SAMPLE_SECONDS:
            return True
        # This delivery turns a sample into a kept video, so it needs a slot of its own.
        kept, sampled = self._session_counts(session["id"])
        return self._slots_used(kept + 1, sampled - 1) <= int(session["videos"])

    def _record_delivery(self, video_id: str) -> None:
        now = self._clock()
        session = self._current_session()
        session_id = self._kept_in(video_id) or (session["id"] if session else None)
        if session_id is None:
            return
        self._db.execute(
            "INSERT INTO session_videos (session_id, video_id, first_at, last_at)"
            " VALUES (?, ?, ?, ?)"
            " ON CONFLICT (session_id, video_id) DO UPDATE SET last_at = excluded.last_at",
            (session_id, video_id, now, now),
        )

    # ---- charging ----

    def _used_seconds(self) -> float:
        row = self._db.execute(
            "SELECT COALESCE(SUM(charged_seconds + unlocked_seconds), 0) AS used"
            " FROM watched WHERE day = ?",
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

    def can_watch(self) -> bool:
        return self._used_seconds() < self.settings()["daily_minutes"] * 60

    def may_deliver(self, video_id: str, start: float, end: float) -> bool:
        """Media already paid for today stays available whatever else has happened."""
        new, _ = add_interval(self._intervals(video_id), start, end)
        return new <= 0 or self.may_open(video_id)

    def charge(self, video_id: str, start: float, end: float, title: str, channel: str) -> float:
        """Record delivered media and return the seconds that were new today."""
        new, merged = add_interval(self._intervals(video_id), start, end)
        if new <= 0:
            return 0.0
        with self._db:
            self._db.execute(
                "INSERT INTO watched"
                " (day, video_id, title, channel, intervals, charged_seconds, last_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?)"
                " ON CONFLICT (day, video_id) DO UPDATE SET"
                " intervals = excluded.intervals,"
                " charged_seconds = charged_seconds + excluded.charged_seconds,"
                " last_at = excluded.last_at",
                (
                    self._day(),
                    video_id,
                    title,
                    channel,
                    json.dumps(merged),
                    new,
                    self._clock(),
                ),
            )
            self._record_delivery(video_id)
        return new

    # ---- reading state ----

    def status(self) -> dict[str, Any]:
        budget = self.settings()["daily_minutes"] * 60
        used = self._used_seconds()
        session = self._current_session()
        details = None
        if session:
            kept, sampled = self._session_counts(session["id"])
            details = {
                "videos_left": max(0, session["videos"] - self._slots_used(kept, sampled)),
                "samples_left": max(0, SAMPLES_PER_SESSION - sampled),
            }
        return {
            "budget_seconds": budget,
            "used_seconds": round(used),
            "remaining_seconds": max(0, round(budget - used)),
            "can_watch": used < budget,
            "resets_at": self._resets_at(),
            "state": self._state(),
            "session": details,
            "next_session_at": self._next_session_at(),
            "session_choices": list(range(1, MAX_SESSION_VIDEOS + 1)),
            "sample_seconds": SAMPLE_SECONDS,
            "now": self._clock(),
        }

    def history(self, days: int = 7) -> dict[str, Any]:
        today = datetime.fromisoformat(self._day())
        totals = []
        for offset in range(days):
            day = (today - timedelta(days=offset)).date().isoformat()
            row = self._db.execute(
                "SELECT COALESCE(SUM(charged_seconds + unlocked_seconds), 0) AS watched"
                " FROM watched WHERE day = ?",
                (day,),
            ).fetchone()
            totals.append({"day": day, "watched_seconds": round(row["watched"])})
        videos = self._db.execute(
            "SELECT video_id, title, channel, charged_seconds + unlocked_seconds AS watched_seconds"
            " FROM watched WHERE day = ? ORDER BY last_at DESC",
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
            "SELECT day, video_id, title, channel, charged_seconds + unlocked_seconds AS watched_seconds"
            f" FROM watched WHERE {' AND '.join(where)} ORDER BY day DESC, last_at DESC LIMIT ? OFFSET ?",
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
