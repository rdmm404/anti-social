"""Strip the clickbait: plain titles, and frames instead of chosen thumbnails."""

import re
import unicodedata
from pathlib import Path

import httpx

# YouTube generates hq1, hq2 and hq3 from the video itself, so they can't be a custom
# thumbnail. hq2 is the midpoint. They're letterboxed to 4:3; the UI crops that to 16:9.
FRAME_URL = "https://i.ytimg.com/vi/{video_id}/hq2.jpg"
BAIT_TAGS = re.compile(
    r"[\[(]\s*(must watch|gone wrong|shocking|insane|not clickbait|emotional|you won'?t believe)[^\])]*[\])]",
    re.IGNORECASE,
)


def clean_title(title: str) -> str:
    """Drop the shouting and decoration."""
    text = "".join(c for c in title if unicodedata.category(c) not in ("So", "Sk", "Cs", "Cf"))
    text = BAIT_TAGS.sub("", text)
    text = re.sub(r"(^|\s)#\w+", " ", text)
    text = re.sub(r"!+", "", text)
    text = re.sub(r"\?+", "?", text)
    text = re.sub(r"\.{4,}", "...", text)
    words = text.split()

    letters = [c for c in text if c.isalpha()]
    mostly_caps = len(letters) > 3 and sum(c.isupper() for c in letters) / len(letters) > 0.7

    def tame(word: str) -> str:
        bare = re.sub(r"\W", "", word)
        if bare.isupper() and (mostly_caps or len(bare) > 4):
            return word.lower()
        return word

    result = " ".join(tame(w) for w in words).strip(" -|:")
    if not result:
        return title.strip()
    # Leave words like "iPhone" alone: only capitalize a first word that's all lowercase.
    first = result.split()[0]
    return result[0].upper() + result[1:] if first.islower() else result


class Frames:
    """Serves frames from disk, so devices never need to reach YouTube's image hosts."""

    def __init__(self, client: httpx.AsyncClient, cache_dir: Path):
        self._client = client
        self._cache_dir = cache_dir
        cache_dir.mkdir(parents=True, exist_ok=True)

    async def get(self, video_id: str) -> Path | None:
        path = self._cache_dir / f"{video_id}.jpg"
        if path.exists():
            return path
        try:
            response = await self._client.get(FRAME_URL.format(video_id=video_id), timeout=10)
        except httpx.HTTPError:
            return None
        if response.status_code != 200:
            return None
        path.write_bytes(response.content)
        return path
