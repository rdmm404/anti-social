"""Everything that knows how YouTube works lives here, so it can be fixed in one place.

yt-dlp does the extraction because it tracks YouTube's changes faster than anything
we could maintain. We use its H.264/AAC DASH files and fetch byte ranges ourselves.
Stream URLs are bound to this server's IP, so all media must pass through it anyway.
"""

import asyncio
import json
import re
import time
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import parse_qs, parse_qsl, quote, urlparse

import httpx

from .media import SegmentIndex, Track, locate_sidx, parse_index
from .ytdlp import ToolError, WorkerFailed, Workers

VIDEO_ID = re.compile(r"^[\w-]{11}$")
CHANNEL_ID = re.compile(r"^(UC[\w-]{20,}|@[\w.-]+)$")
PLAYLIST_ID = re.compile(r"^[\w-]{10,}$")
FORMAT_ID = re.compile(r"^[\w-]{1,20}$")
MAX_H264_HEIGHT = 1080  # YouTube has no H.264 above this; higher tracks are AV1.
MAX_HEIGHT = 2160
COMMENT_LIMIT = 20
# Asking for just this client takes half the time of yt-dlp's default set, and our MP4
# tracks come from it anyway. If it stops working, stream() falls back to the default.
FAST_CLIENT = "player_client=visionos"
# We play the direct MP4 files, so the HLS and DASH manifests are wasted downloads.
# Leaving out the watch page too is not an option: it supplies the visitor data that
# keeps YouTube from asking for a sign-in.
FAST_STREAM = f"{FAST_CLIENT};skip=hls,dash"
SEARCH_RESULTS = 20
CHANNEL_RESULTS = 30
PLAYLIST_RESULTS = 500
HEAD_BYTES = 64 * 1024
YOUTUBE_HOSTS = {
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtu.be",
    "www.youtube-nocookie.com",
}
ID_PATHS = {"shorts", "live", "embed", "v"}


class ExtractionError(Exception):
    """YouTube wouldn't give us this. The message is safe to show to the user."""


@dataclass(frozen=True)
class Listing:
    id: str
    title: str
    channel: str | None
    channel_id: str | None
    duration: int | None


@dataclass
class Stream:
    id: str
    title: str
    channel: str
    channel_id: str | None
    duration: int
    description: str
    videos: list[Track]
    audio: Track
    urls: dict[str, str]
    headers: dict[str, str]
    expires_at: float
    indexes: dict[str, SegmentIndex] = field(default_factory=dict)


def _seconds(value: str) -> int | None:
    """Read YouTube's start times: 90, 90s or 1h2m3s."""
    match = re.fullmatch(r"(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?", value)
    if not value or not match:
        return None
    hours, minutes, seconds = (int(part or 0) for part in match.groups())
    return hours * 3600 + minutes * 60 + seconds


def app_route(path: str, query: Mapping[str, str]) -> str | None:
    """Where the path and query of a YouTube URL land in this app, if anywhere."""
    parts = [part for part in path.split("/") if part]
    if not parts:
        return None
    first, last = parts[0], parts[-1]
    video_id = None
    if first == "watch":
        video_id = query.get("v")
    elif first in ID_PATHS and len(parts) > 1:
        video_id = parts[1]
    elif len(parts) == 1:  # youtu.be/<id>
        video_id = first
    if video_id and VIDEO_ID.match(video_id):
        start = _seconds(query.get("t") or query.get("start") or "")
        return f"#/watch/{video_id}" + (f"?t={start}" if start else "")
    if first == "results" and query.get("search_query"):
        return f"#/search/{quote(query['search_query'], safe='')}"
    if first == "playlist" and PLAYLIST_ID.match(query.get("list", "")):
        return f"#/playlists?import={query['list']}"
    channel = last if first == "channel" else first
    if (first == "channel" or first.startswith("@")) and CHANNEL_ID.match(channel):
        return f"#/channel/{channel}"
    return None


def shared_route(url: str, text: str) -> str:
    """Where something shared with the app goes. Apps put the link in either field."""
    link = re.search(r"https?://\S+", f"{url} {text}")
    if link:
        parsed = urlparse(link.group())
        if (parsed.hostname or "").lower() in YOUTUBE_HOSTS:
            return app_route(parsed.path, dict(parse_qsl(parsed.query))) or "#/"
    words = (text or url).strip()
    return f"#/search/{quote(words[:200], safe='')}" if words else "#/"


def _expiry(url: str) -> float:
    query = parse_qs(urlparse(url).query)
    try:
        return float(query["expire"][0]) - 600
    except (KeyError, ValueError):
        return time.time() + 5 * 3600


def _reason(output: str) -> str:
    """The line of yt-dlp's output that says what went wrong."""
    lines = output.strip().splitlines()
    reason = next((l for l in reversed(lines) if l.startswith("ERROR")), "yt-dlp failed")
    return reason.removeprefix("ERROR:").strip()[:300]


def _codec_tag(value: str) -> str:
    return value.split(",")[0].strip()


def stream_from_info(info: dict[str, Any]) -> Stream:
    """Choose one MP4 video track per height and the best AAC track from yt-dlp's formats.

    H.264 plays everywhere, so it's used up to 1080p. Above that only AV1 fits our MP4
    pipeline; hls.js hides those tracks on browsers that can't decode them.
    """
    if info.get("is_live") or info.get("live_status") in ("is_live", "is_upcoming"):
        raise ExtractionError("Live streams aren't supported.")
    formats = [f for f in info.get("formats", []) if f.get("protocol") == "https"]

    best_by_height: dict[int, dict[str, Any]] = {}
    for f in formats:
        height = f.get("height")
        if not height or height > MAX_HEIGHT or f.get("acodec") != "none":
            continue
        vcodec = f.get("vcodec") or ""
        wanted = "avc1" if height <= MAX_H264_HEIGHT else "av01"
        if not vcodec.startswith(wanted) or f.get("dynamic_range", "SDR") != "SDR":
            continue
        current = best_by_height.get(height)
        if current is None or (f.get("fps") or 0) > (current.get("fps") or 0):
            best_by_height[height] = f

    # Dubbed videos list several languages; language_preference marks the original.
    audios = [
        f
        for f in formats
        if f.get("vcodec") == "none"
        and (f.get("acodec") or "").startswith("mp4a.40.2")
        and "drc" not in f["format_id"]
    ]
    if not best_by_height or not audios:
        raise ExtractionError("No playable format for this video.")
    audio_format = max(audios, key=lambda f: f.get("language_preference") or 0)

    chosen = [best_by_height[h] for h in sorted(best_by_height)]
    videos = [
        Track(
            f["format_id"],
            _codec_tag(f["vcodec"]),
            int((f.get("tbr") or 1000) * 1000),
            f.get("width"),
            f["height"],
            round(f["fps"]) if f.get("fps") else None,
        )
        for f in chosen
    ]
    audio = Track(
        audio_format["format_id"],
        _codec_tag(audio_format["acodec"]),
        int((audio_format.get("tbr") or 128) * 1000),
    )
    urls = {f["format_id"]: f["url"] for f in [*chosen, audio_format]}
    return Stream(
        id=info["id"],
        title=info.get("title") or info["id"],
        channel=info.get("channel") or info.get("uploader") or "",
        channel_id=info.get("channel_id"),
        duration=int(info.get("duration") or 0),
        description=info.get("description") or "",
        videos=videos,
        audio=audio,
        urls=urls,
        headers=dict(chosen[0].get("http_headers") or {}),
        expires_at=min(_expiry(u) for u in urls.values()),
    )


def listings_from_info(info: dict[str, Any]) -> list[Listing]:
    results = []
    for entry in info.get("entries") or []:
        url = entry.get("url") or ""
        video_id = entry.get("id") or ""
        if "/shorts/" in url or not VIDEO_ID.match(video_id):
            continue
        if entry.get("live_status") in ("is_live", "is_upcoming"):
            continue
        results.append(
            Listing(
                id=video_id,
                title=entry.get("title") or video_id,
                channel=entry.get("channel") or entry.get("uploader"),
                channel_id=entry.get("channel_id"),
                duration=int(entry["duration"]) if entry.get("duration") else None,
            )
        )
    return results


def comments_from_info(info: dict[str, Any]) -> list[dict[str, Any]]:
    """Top-level comments only, with only the fields the page shows."""
    return [
        {
            "author": c.get("author") or "",
            "text": c.get("text") or "",
            "when": c.get("_time_text") or "",
            "likes": c.get("like_count") or 0,
            "pinned": bool(c.get("is_pinned")),
        }
        for c in info.get("comments") or []
        if c.get("parent", "root") == "root"
    ][:COMMENT_LIMIT]


class YouTube:
    def __init__(
        self, client: httpx.AsyncClient, ytdlp_bin: str = "yt-dlp", workers: Workers | None = None
    ):
        self._client = client
        self._bin = ytdlp_bin
        self._workers = workers
        self._streams: dict[str, Stream] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def _ytdlp(self, *args: str) -> dict[str, Any]:
        """Run yt-dlp with these arguments, the URL last. Failures become ExtractionError."""
        if self._workers:
            try:
                return await self._workers.run(list(args))
            except ToolError as error:
                raise ExtractionError(_reason(str(error))) from None
            except TimeoutError:
                raise ExtractionError("YouTube took too long to respond.") from None
            except WorkerFailed:
                pass  # A one-off process is slower but doesn't depend on the worker.
        return await self._ytdlp_once(*args)

    async def _ytdlp_once(self, *args: str) -> dict[str, Any]:
        process = await asyncio.create_subprocess_exec(
            self._bin,
            "--ignore-config",
            "--no-warnings",
            "--dump-single-json",
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            out, err = await asyncio.wait_for(process.communicate(), timeout=90)
        except TimeoutError:
            process.kill()
            await process.wait()
            raise ExtractionError("YouTube took too long to respond.") from None
        if process.returncode != 0:
            raise ExtractionError(_reason(err.decode(errors="replace")))
        info: dict[str, Any] = json.loads(out)
        return info

    # ---- browsing ----

    async def search(self, query: str) -> list[Listing]:
        info = await self._ytdlp("--flat-playlist", f"ytsearch{SEARCH_RESULTS}:{query}")
        return listings_from_info(info)

    async def channel(self, channel_id: str, offset: int = 0) -> tuple[str, list[Listing], bool]:
        """One page of a channel's videos, newest first, and whether there may be more."""
        if not CHANNEL_ID.match(channel_id):
            raise ExtractionError("Not a channel id.")
        path = channel_id if channel_id.startswith("@") else f"channel/{channel_id}"
        info = await self._ytdlp(
            "--flat-playlist",
            "--playlist-items",
            f"{offset + 1}:{offset + CHANNEL_RESULTS}",
            f"https://www.youtube.com/{path}/videos",
        )
        name = info.get("channel") or info.get("uploader") or channel_id
        # Counted before filtering: a page of only hidden videos doesn't mean the end.
        has_more = len(info.get("entries") or []) == CHANNEL_RESULTS
        return name, listings_from_info(info), has_more

    async def playlist(self, url: str) -> tuple[str, list[Listing]]:
        """Accepts a playlist URL or bare id. Only public and unlisted lists are readable."""
        raw = url.strip()
        playlist_id = (parse_qs(urlparse(raw).query).get("list") or [raw])[0]
        if not PLAYLIST_ID.match(playlist_id):
            raise ExtractionError("That doesn't look like a YouTube playlist link.")
        info = await self._ytdlp(
            "--flat-playlist",
            "--playlist-end",
            str(PLAYLIST_RESULTS),
            f"https://www.youtube.com/playlist?list={playlist_id}",
        )
        return info.get("title") or "Imported playlist", listings_from_info(info)

    async def comments(self, video_id: str) -> list[dict[str, Any]]:
        if not VIDEO_ID.match(video_id):
            raise ExtractionError("Not a video id.")
        info = await self._ytdlp(
            "--skip-download",
            "--write-comments",
            "--ignore-no-formats-error",
            "--extractor-args",
            f"youtube:{FAST_CLIENT};max_comments={COMMENT_LIMIT},all,0,0;comment_sort=top",
            f"https://www.youtube.com/watch?v={video_id}",
        )
        return comments_from_info(info)

    # ---- streaming ----

    async def stream(self, video_id: str, refresh: bool = False) -> Stream:
        if not VIDEO_ID.match(video_id):
            raise ExtractionError("Not a video id.")
        async with self._locks.setdefault(video_id, asyncio.Lock()):
            cached = self._streams.get(video_id)
            if cached and not refresh and cached.expires_at > time.time():
                return cached
            url = f"https://www.youtube.com/watch?v={video_id}"
            try:
                info = await self._ytdlp("--extractor-args", f"youtube:{FAST_STREAM}", url)
                stream = stream_from_info(info)
            except ExtractionError:
                stream = stream_from_info(await self._ytdlp(url))
            if cached:
                stream.indexes = cached.indexes  # Byte layout survives a URL refresh.
            self._streams[video_id] = stream
            return stream

    async def open_range(self, video_id: str, format_id: str, start: int, end: int) -> httpx.Response:
        """Open an upstream byte range; the caller must aclose() the response."""
        stream = await self.stream(video_id)
        for attempt in (1, 2):
            url = stream.urls.get(format_id)
            if url is None:
                raise ExtractionError("Unknown format.")
            request = self._client.build_request(
                "GET", url, headers={**stream.headers, "Range": f"bytes={start}-{end}"}, timeout=30
            )
            response = await self._client.send(request, stream=True)
            if response.status_code in (200, 206):
                return response
            await response.aclose()
            if attempt == 1 and response.status_code in (403, 410):
                stream = await self.stream(video_id, refresh=True)  # The URL went stale.
                continue
            raise ExtractionError(f"YouTube refused the stream ({response.status_code}).")
        raise AssertionError("unreachable")

    async def _read_range(self, video_id: str, format_id: str, start: int, end: int) -> bytes:
        response = await self.open_range(video_id, format_id, start, end)
        try:
            return await response.aread()
        finally:
            await response.aclose()

    async def index(self, video_id: str, format_id: str) -> SegmentIndex:
        stream = await self.stream(video_id)
        if format_id in stream.indexes:
            return stream.indexes[format_id]
        head = await self._read_range(video_id, format_id, 0, HEAD_BYTES - 1)
        located = locate_sidx(head)
        if located is None:
            raise ExtractionError("Couldn't read this video's index.")
        offset, size = located
        if offset + size > len(head):  # Long videos have sidx boxes past the first read.
            head = await self._read_range(video_id, format_id, 0, offset + size - 1)
        index = parse_index(head, offset, size)
        stream.indexes[format_id] = index
        return index
