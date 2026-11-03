"""Install yt-dlp into the data volume, where it can update itself, then start the app.

A copy baked into the image would go stale between rebuilds, and a stale yt-dlp is the
most likely reason for playback to break.
"""

import os
import subprocess
from pathlib import Path

binary = Path(os.environ["ANTI_SOCIAL_YTDLP"])
venv = binary.parent.parent


def works() -> bool:
    try:
        return subprocess.run([binary, "--version"], capture_output=True).returncode == 0
    except OSError:
        return False


if not works():
    # Also repairs a venv whose Python went away in an image rebuild.
    subprocess.run(["uv", "venv", "--clear", "--python", "/usr/local/bin/python3", venv], check=True)
    subprocess.run(
        ["uv", "pip", "install", "--python", venv / "bin" / "python", "yt-dlp[default]"], check=True
    )

os.execv("/app/.venv/bin/anti-social", ["anti-social"])
