"""Server-level configuration, read from the environment.

Limits live in the database behind the cooling-off rule. Values here are only
the first-run seeds and things that are deliberately not editable from the app.
"""

import os
from dataclasses import dataclass, field
from pathlib import Path

from .schedule import Schedule


def _int(name: str, default: int) -> int:
    return int(os.environ.get(name, default))


@dataclass(frozen=True)
class Config:
    data_dir: Path = field(
        default_factory=lambda: Path(os.environ.get("ANTI_SOCIAL_DATA", "data"))
    )
    host: str = field(
        default_factory=lambda: os.environ.get("ANTI_SOCIAL_HOST", "127.0.0.1")
    )
    port: int = field(default_factory=lambda: _int("ANTI_SOCIAL_PORT", 8484))
    ytdlp_bin: str = field(
        default_factory=lambda: os.environ.get("ANTI_SOCIAL_YTDLP", "yt-dlp")
    )
    # Long-lived yt-dlp processes, which is how many requests run at once. 0 starts one per request.
    ytdlp_workers: int = field(default_factory=lambda: _int("ANTI_SOCIAL_YTDLP_WORKERS", 2))
    # A command to run daily, so a deployment's yt-dlp doesn't go stale.
    ytdlp_update_command: str = field(
        default_factory=lambda: os.environ.get("ANTI_SOCIAL_YTDLP_UPDATE", "")
    )
    # The budget day rolls over at this local hour, so a 1am binge counts
    # against the evening it belongs to.
    day_start_hour: int = field(default_factory=lambda: _int("ANTI_SOCIAL_DAY_START", 4))
    cooling_off_hours: int = field(
        default_factory=lambda: _int("ANTI_SOCIAL_COOLING_OFF_HOURS", 72)
    )
    # When watching is off no matter what. Here and not in the app, so that
    # lifting it takes a redeploy instead of a tap.
    blocked: Schedule = field(
        default_factory=lambda: Schedule.parse(os.environ.get("ANTI_SOCIAL_BLOCKED", ""))
    )
    # First-run seeds. Ignored once the database exists.
    seed_daily_minutes: int = field(
        default_factory=lambda: _int("ANTI_SOCIAL_DAILY_MINUTES", 60)
    )
    seed_unlocks_per_day: int = field(
        default_factory=lambda: _int("ANTI_SOCIAL_UNLOCKS_PER_DAY", 2)
    )
    seed_unlock_minutes: int = field(
        default_factory=lambda: _int("ANTI_SOCIAL_UNLOCK_MINUTES", 30)
    )
    seed_unlock_wait_seconds: int = field(
        default_factory=lambda: _int("ANTI_SOCIAL_UNLOCK_WAIT_SECONDS", 60)
    )

    @property
    def db_path(self) -> Path:
        return self.data_dir / "anti_social.db"

    @property
    def thumb_dir(self) -> Path:
        return self.data_dir / "thumbs"
