"""HTTP surface. The rules live in Ledger; YouTube's quirks live in YouTube."""

import asyncio
import re
import shlex
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import httpx
import uvicorn
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import (
    FileResponse,
    JSONResponse,
    PlainTextResponse,
    RedirectResponse,
    StreamingResponse,
)
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from .branding import Frames, clean_title
from .config import Config
from .ledger import Ledger, LedgerError
from .media import master_playlist, media_playlist
from .playlists import Playlists
from .youtube import (
    CHANNEL_RESULTS,
    FORMAT_ID,
    VIDEO_ID,
    ExtractionError,
    Listing,
    YouTube,
    app_route,
    shared_route,
)
from .ytdlp import Workers

STATIC_DIR = Path(__file__).parent / "static"
HISTORY_PAGE = 50
YTDLP_UPDATE_INTERVAL = 24 * 3600
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
NO_STORE = {"Cache-Control": "no-store"}
OUT_OF_TIME = {"error": "out_of_time", "message": "You're out of time for today."}


class SettingsChange(BaseModel):
    changes: dict[str, int]


class NewPlaylist(BaseModel):
    name: str


class PlaylistImport(BaseModel):
    url: str


class PlaylistItem(BaseModel):
    id: str
    title: str
    channel: str = ""
    duration: int | None = None


class Subscription(BaseModel):
    channel_id: str
    name: str


async def _nothing() -> None:
    pass


async def keep_ytdlp_current(command: str, on_updated: Callable[[], Awaitable[None]]) -> None:
    while True:
        process = await asyncio.create_subprocess_exec(
            *shlex.split(command),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        await process.wait()
        await on_updated()  # Running workers still have the old yt-dlp loaded.
        await asyncio.sleep(YTDLP_UPDATE_INTERVAL)


def create_app(
    config: Config | None = None,
    ledger: Ledger | None = None,
    youtube: YouTube | None = None,
    frames: Frames | None = None,
) -> FastAPI:
    config = config or Config()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        client = httpx.AsyncClient(follow_redirects=True, limits=httpx.Limits(max_connections=64))
        app.state.ledger = ledger or Ledger(config)
        app.state.playlists = Playlists(config.db_path)
        workers = None if youtube else Workers.for_binary(config.ytdlp_bin, config.ytdlp_workers)
        app.state.youtube = youtube or YouTube(client, config.ytdlp_bin, workers)
        app.state.frames = frames or Frames(client, config.thumb_dir)
        background = []
        if workers:
            background.append(asyncio.create_task(workers.restart()))  # Ready for the first request.
        if config.ytdlp_update_command:
            on_updated = workers.restart if workers else _nothing
            background.append(
                asyncio.create_task(keep_ytdlp_current(config.ytdlp_update_command, on_updated))
            )
        yield
        for task in background:
            task.cancel()
        if workers:
            workers.close()
        await client.aclose()

    app = FastAPI(title="anti-social", lifespan=lifespan, docs_url=None, redoc_url=None)

    @app.exception_handler(ExtractionError)
    async def extraction_failed(_: Request, exc: ExtractionError) -> JSONResponse:
        return JSONResponse({"error": "youtube", "message": str(exc)}, status_code=502)

    @app.exception_handler(LedgerError)
    async def refused(_: Request, exc: LedgerError) -> JSONResponse:
        return JSONResponse({"error": "refused", "message": str(exc)}, status_code=409)

    @app.exception_handler(404)
    async def youtube_url_or_not_found(request: Request, exc: HTTPException) -> Response:
        """Swapping a YouTube link's domain for this server's opens the same thing here."""
        route = app_route(request.url.path, request.query_params)
        if route and request.method == "GET":
            return RedirectResponse(f"/{route}", status_code=302)
        return JSONResponse({"detail": exc.detail}, status_code=404)

    def require_time() -> JSONResponse | None:
        if app.state.ledger.can_watch():
            return None
        return JSONResponse(OUT_OF_TIME, status_code=402, headers=NO_STORE)

    def present(listings: list[Listing]) -> list[dict[str, Any]]:
        return [
            {
                "id": listing.id,
                "title": clean_title(listing.title),
                "channel": listing.channel,
                "channel_id": listing.channel_id,
                "duration": listing.duration,
            }
            for listing in listings
        ]

    # ---- status, unlocks, settings ----

    @app.get("/api/status")
    async def status() -> Any:
        return app.state.ledger.status()

    @app.post("/api/unlock")
    async def unlock() -> Any:
        return app.state.ledger.start_unlock()

    @app.get("/api/settings")
    async def settings() -> Any:
        ledger: Ledger = app.state.ledger
        return {
            "settings": ledger.settings(),
            "pending": ledger.pending_changes(),
            "cooling_off_hours": config.cooling_off_hours,
        }

    @app.post("/api/settings")
    async def change_settings(body: SettingsChange) -> Any:
        return app.state.ledger.change_settings(body.changes)

    @app.delete("/api/settings/pending/{change_id}")
    async def cancel_change(change_id: int) -> Response:
        app.state.ledger.cancel_change(change_id)
        return Response(status_code=204)

    @app.get("/api/history")
    async def history() -> Any:
        return app.state.ledger.history()

    @app.get("/api/history/videos")
    async def watched_videos(
        q: str = "", start: str | None = None, end: str | None = None, offset: int = 0
    ) -> Any:
        for day in (start, end):
            if day and not DAY.match(day):
                raise HTTPException(400, "Dates must look like 2026-11-03.")
        return {"videos": app.state.ledger.search_watched(q, start, end, HISTORY_PAGE, max(0, offset))}

    # ---- playlists: reading one needs no YouTube, and watching is what gets gated ----

    @app.get("/api/playlists")
    async def playlists() -> Any:
        return {"playlists": app.state.playlists.all()}

    @app.post("/api/playlists")
    async def create_playlist(body: NewPlaylist) -> Any:
        return {"id": app.state.playlists.create(body.name)}

    @app.post("/api/playlists/import")
    async def import_playlist(body: PlaylistImport) -> Any:
        if blocked := require_time():
            return blocked
        name, listings = await app.state.youtube.playlist(body.url)
        store: Playlists = app.state.playlists
        playlist_id = store.create(name)
        store.add(playlist_id, present(listings))
        return {"id": playlist_id, "imported": len(listings)}

    @app.get("/api/playlists/{playlist_id}")
    async def playlist(playlist_id: int) -> Any:
        found = app.state.playlists.get(playlist_id)
        if found is None:
            raise HTTPException(404)
        return found

    @app.delete("/api/playlists/{playlist_id}")
    async def delete_playlist(playlist_id: int) -> Response:
        app.state.playlists.delete(playlist_id)
        return Response(status_code=204)

    @app.post("/api/playlists/{playlist_id}/items")
    async def add_to_playlist(playlist_id: int, body: PlaylistItem) -> Response:
        if not VIDEO_ID.match(body.id):
            raise HTTPException(400)
        app.state.playlists.add(playlist_id, [body.model_dump()])
        return Response(status_code=204)

    @app.delete("/api/playlists/{playlist_id}/items/{video_id}")
    async def remove_from_playlist(playlist_id: int, video_id: str) -> Response:
        app.state.playlists.remove(playlist_id, video_id)
        return Response(status_code=204)

    # ---- browsing: closed when there's no time left, so there's nothing to browse ----

    @app.get("/api/search")
    async def search(q: str) -> Any:
        if blocked := require_time():
            return blocked
        query = q.strip()
        if not query:
            return {"results": []}
        return {"results": present(await app.state.youtube.search(query[:200]))}

    @app.get("/api/channel/{channel_id}")
    async def channel(channel_id: str, offset: int = 0) -> Any:
        if blocked := require_time():
            return blocked
        offset = max(0, offset)
        name, listings, has_more = await app.state.youtube.channel(channel_id, offset)
        return {
            "id": channel_id,
            "name": name,
            "results": present(listings),
            "next_offset": offset + CHANNEL_RESULTS if has_more else None,
        }

    @app.get("/api/subscriptions")
    async def subscriptions() -> Any:
        return {"subscriptions": app.state.ledger.subscriptions()}

    @app.post("/api/subscriptions")
    async def subscribe(body: Subscription) -> Response:
        app.state.ledger.subscribe(body.channel_id, body.name[:200])
        return Response(status_code=204)

    @app.delete("/api/subscriptions/{channel_id}")
    async def unsubscribe(channel_id: str) -> Response:
        app.state.ledger.unsubscribe(channel_id)
        return Response(status_code=204)

    @app.get("/thumb/{video_id}")
    async def thumbnail(video_id: str) -> FileResponse:
        if not VIDEO_ID.match(video_id):
            raise HTTPException(404)
        path = await app.state.frames.get(video_id)
        if path is None:
            raise HTTPException(404)
        return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "max-age=604800"})

    # ---- watching ----

    @app.get("/api/video/{video_id}")
    async def video(video_id: str) -> Any:
        if blocked := require_time():
            return blocked
        stream = await app.state.youtube.stream(video_id)
        return {
            "id": stream.id,
            "channel": stream.channel,
            "channel_id": stream.channel_id,
            "duration": stream.duration,
            "audio_format": stream.audio.format_id,
            "title": clean_title(stream.title),
            "description": stream.description,
        }

    @app.get("/api/video/{video_id}/comments")
    async def comments(video_id: str) -> Any:
        if blocked := require_time():
            return blocked
        return {"comments": await app.state.youtube.comments(video_id)}

    def check_format(video_id: str, format_id: str) -> None:
        if not VIDEO_ID.match(video_id) or not FORMAT_ID.match(format_id):
            raise HTTPException(404)

    @app.get("/media/{video_id}/master.m3u8")
    async def master(video_id: str) -> Any:
        if blocked := require_time():
            return blocked
        stream = await app.state.youtube.stream(video_id)
        return PlainTextResponse(
            master_playlist(stream.videos, stream.audio),
            media_type="application/vnd.apple.mpegurl",
            headers=NO_STORE,
        )

    @app.get("/media/{video_id}/{format_id}/index.m3u8")
    async def media_index(video_id: str, format_id: str) -> PlainTextResponse:
        check_format(video_id, format_id)
        index = await app.state.youtube.index(video_id, format_id)
        return PlainTextResponse(
            media_playlist(index),
            media_type="application/vnd.apple.mpegurl",
            headers=NO_STORE,
        )

    def relay(upstream: httpx.Response, media_type: str, length: int) -> StreamingResponse:
        async def body() -> AsyncIterator[bytes]:
            try:
                async for chunk in upstream.aiter_bytes(64 * 1024):
                    yield chunk
            finally:
                await upstream.aclose()

        return StreamingResponse(
            body(),
            media_type=media_type,
            headers={**NO_STORE, "Content-Length": str(length)},
        )

    @app.get("/media/{video_id}/{format_id}/init.mp4")
    async def media_init(video_id: str, format_id: str) -> StreamingResponse:
        check_format(video_id, format_id)
        index = await app.state.youtube.index(video_id, format_id)
        upstream = await app.state.youtube.open_range(video_id, format_id, 0, index.init_end_byte)
        return relay(upstream, "video/mp4", index.init_end_byte + 1)

    @app.get("/media/{video_id}/{format_id}/seg/{number}.m4s")
    async def media_segment(video_id: str, format_id: str, number: int) -> Response:
        """The enforcement point: no time, no bytes, whatever the client thinks."""
        check_format(video_id, format_id)
        youtube: YouTube = app.state.youtube
        ledger: Ledger = app.state.ledger
        index = await youtube.index(video_id, format_id)
        if not 0 <= number < len(index.segments):
            raise HTTPException(404)
        segment = index.segments[number]
        if not ledger.may_deliver(video_id, segment.start_time, segment.end_time):
            return JSONResponse(OUT_OF_TIME, status_code=402, headers=NO_STORE)

        upstream = await youtube.open_range(
            video_id, format_id, segment.start_byte, segment.end_byte
        )
        stream = await youtube.stream(video_id)
        ledger.charge(
            video_id, segment.start_time, segment.end_time, clean_title(stream.title), stream.channel
        )
        return relay(upstream, "video/mp4", segment.end_byte - segment.start_byte + 1)

    @app.get("/share")
    async def share(url: str = "", text: str = "") -> RedirectResponse:
        return RedirectResponse(f"/{shared_route(url, text)}", status_code=302)

    @app.get("/")
    async def index_page() -> FileResponse:
        return FileResponse(STATIC_DIR / "index.html", headers=NO_STORE)

    app.mount("/", StaticFiles(directory=STATIC_DIR), name="static")
    return app


def run() -> None:
    config = Config()
    uvicorn.run(create_app(config), host=config.host, port=config.port)


if __name__ == "__main__":
    run()
