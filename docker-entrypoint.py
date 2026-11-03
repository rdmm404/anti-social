"""Fetch yt-dlp into the data volume, where it can update itself, then start the app.

A copy baked into the image would go stale between rebuilds, and a stale yt-dlp is the
most likely reason for playback to break.
"""

import os
import stat
import urllib.request
from pathlib import Path

RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

binary = Path(os.environ["ANTI_SOCIAL_YTDLP"])
if not binary.exists():
    binary.parent.mkdir(parents=True, exist_ok=True)
    partial = binary.with_suffix(".partial")
    urllib.request.urlretrieve(RELEASE, partial)
    partial.chmod(partial.stat().st_mode | stat.S_IEXEC)
    partial.rename(binary)

os.execv("/app/.venv/bin/anti-social", ["anti-social"])
