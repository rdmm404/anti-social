"""Saved lists of videos. Stored here, so reading one never needs YouTube."""

import sqlite3
import time
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

from .ledger import LedgerError

SCHEMA = """
CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_items (
    playlist_id INTEGER NOT NULL,
    video_id TEXT NOT NULL,
    title TEXT NOT NULL,
    channel TEXT NOT NULL,
    duration INTEGER,
    position INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, video_id)
);
"""

MAX_NAME = 100


class Playlists:
    def __init__(self, db_path: Path, clock: Callable[[], float] = time.time):
        self._clock = clock
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(db_path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)

    def all(self) -> list[dict[str, Any]]:
        rows = self._db.execute(
            "SELECT p.id, p.name, COUNT(i.video_id) AS count FROM playlists p"
            " LEFT JOIN playlist_items i ON i.playlist_id = p.id"
            " GROUP BY p.id ORDER BY p.name COLLATE NOCASE"
        ).fetchall()
        return [dict(r) for r in rows]

    def create(self, name: str) -> int:
        cleaned = name.strip()[:MAX_NAME]
        if not cleaned:
            raise LedgerError("Give the playlist a name.")
        with self._db:
            cursor = self._db.execute(
                "INSERT INTO playlists (name, created_at) VALUES (?, ?)", (cleaned, self._clock())
            )
        assert cursor.lastrowid is not None
        return cursor.lastrowid

    def delete(self, playlist_id: int) -> None:
        with self._db:
            self._db.execute("DELETE FROM playlist_items WHERE playlist_id = ?", (playlist_id,))
            self._db.execute("DELETE FROM playlists WHERE id = ?", (playlist_id,))

    def get(self, playlist_id: int) -> dict[str, Any] | None:
        playlist = self._db.execute(
            "SELECT id, name FROM playlists WHERE id = ?", (playlist_id,)
        ).fetchone()
        if playlist is None:
            return None
        items = self._db.execute(
            "SELECT video_id AS id, title, channel, duration FROM playlist_items"
            " WHERE playlist_id = ? ORDER BY position",
            (playlist_id,),
        ).fetchall()
        return {**dict(playlist), "items": [dict(i) for i in items]}

    def add(self, playlist_id: int, items: Iterable[dict[str, Any]]) -> None:
        """Append videos in order. A video already in the list keeps its place."""
        if self.get(playlist_id) is None:
            raise LedgerError("That playlist no longer exists.")
        row = self._db.execute(
            "SELECT COALESCE(MAX(position), 0) AS last FROM playlist_items WHERE playlist_id = ?",
            (playlist_id,),
        ).fetchone()
        position = int(row["last"])
        with self._db:
            for item in items:
                position += 1
                self._db.execute(
                    "INSERT OR IGNORE INTO playlist_items"
                    " (playlist_id, video_id, title, channel, duration, position)"
                    " VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        playlist_id,
                        item["id"],
                        item["title"],
                        item.get("channel") or "",
                        item.get("duration"),
                        position,
                    ),
                )

    def remove(self, playlist_id: int, video_id: str) -> None:
        with self._db:
            self._db.execute(
                "DELETE FROM playlist_items WHERE playlist_id = ? AND video_id = ?",
                (playlist_id, video_id),
            )
