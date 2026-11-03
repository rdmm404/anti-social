import asyncio
import struct
from pathlib import Path
from typing import cast

import httpx
import pytest
from fastapi.testclient import TestClient

from anti_social.branding import FRAME_URL, Frames, clean_title
from anti_social.config import Config
from anti_social.ledger import Ledger, LedgerError, add_interval
from anti_social.main import create_app
from anti_social.playlists import Playlists
from anti_social.media import SegmentIndex, Segment, Track, locate_sidx, media_playlist, parse_index
from anti_social.youtube import Stream, YouTube, comments_from_info, listings_from_info, stream_from_info

HOUR = 3600


class Clock:
    # Noon local time on an arbitrary day, so tests sit far from the 4am rollover.
    def __init__(self):
        import datetime

        self.now = datetime.datetime(2026, 6, 15, 12).timestamp()

    def __call__(self):
        return self.now


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def config(tmp_path: Path):
    return Config(
        data_dir=tmp_path,
        seed_daily_minutes=10,
        seed_unlocks_per_day=2,
        seed_unlock_minutes=30,
        seed_unlock_wait_seconds=60,
    )


@pytest.fixture
def ledger(config, clock):
    return Ledger(config, clock)


# ---- charging ----


def test_add_interval_counts_only_new_time():
    new, merged = add_interval([[0, 10], [20, 30]], 5, 25)
    assert new == 10
    assert merged == [[0, 30]]


def test_audio_and_video_tracks_charge_once(ledger):
    ledger.charge("v", 0, 10, "t", "c")
    ledger.charge("v", 0, 5, "t", "c")
    ledger.charge("v", 5, 10, "t", "c")
    assert ledger.status()["used_seconds"] == 10


def test_budget_blocks_new_media_but_not_already_paid_media(ledger):
    ledger.charge("v", 0, 600, "t", "c")
    assert not ledger.can_watch()
    assert ledger.may_deliver("v", 100, 110)
    assert not ledger.may_deliver("v", 600, 610)
    assert not ledger.may_deliver("other", 0, 10)


def test_budget_resets_at_day_start_not_midnight(ledger, clock):
    ledger.charge("v", 0, 600, "t", "c")
    clock.now += 13 * HOUR  # 01:00 next calendar day: still the same budget day.
    assert not ledger.can_watch()
    clock.now += 4 * HOUR  # 05:00: new budget day.
    assert ledger.can_watch()
    assert ledger.status()["used_seconds"] == 0


# ---- unlocks ----


def test_unlock_waits_then_opens_without_spending_budget(ledger, clock):
    ledger.charge("v", 0, 600, "t", "c")
    ledger.start_unlock()
    assert not ledger.can_watch()  # Still in the wait.
    clock.now += 61
    assert ledger.can_watch()
    ledger.charge("v", 600, 700, "t", "c")
    assert ledger.status()["used_seconds"] == 600
    clock.now += 31 * 60
    assert not ledger.can_watch()


def test_unlock_refused_while_budget_remains(ledger):
    ledger.charge("v", 0, 300, "t", "c")
    assert ledger.status()["budget_spent"] is False
    with pytest.raises(LedgerError):
        ledger.start_unlock()
    ledger.charge("v", 300, 600, "t", "c")
    assert ledger.status()["budget_spent"] is True
    ledger.start_unlock()


def test_unlocks_are_limited_per_day_and_cannot_overlap(ledger, clock):
    ledger.charge("v", 0, 600, "t", "c")
    ledger.start_unlock()
    with pytest.raises(LedgerError):
        ledger.start_unlock()
    clock.now += 32 * 60
    ledger.start_unlock()
    clock.now += 32 * 60
    with pytest.raises(LedgerError):
        ledger.start_unlock()


# ---- cooling off ----


def test_tightening_is_immediate(ledger):
    result = ledger.change_settings({"daily_minutes": 5})
    assert result["applied"] == ["daily_minutes"]
    assert ledger.settings()["daily_minutes"] == 5


def test_loosening_waits_72_hours(ledger, clock):
    result = ledger.change_settings({"daily_minutes": 100})
    assert result["queued"] == ["daily_minutes"]
    clock.now += 71 * HOUR
    assert ledger.settings()["daily_minutes"] == 10
    clock.now += 2 * HOUR
    assert ledger.settings()["daily_minutes"] == 100
    assert ledger.pending_changes() == []


def test_shorter_unlock_wait_counts_as_loosening(ledger):
    assert ledger.change_settings({"unlock_wait_seconds": 0})["queued"] == ["unlock_wait_seconds"]
    assert ledger.change_settings({"unlock_wait_seconds": 120})["applied"] == ["unlock_wait_seconds"]


def test_changing_a_queued_value_restarts_the_clock(ledger, clock):
    ledger.change_settings({"daily_minutes": 100})
    clock.now += 71 * HOUR
    ledger.change_settings({"daily_minutes": 90})
    clock.now += 2 * HOUR
    assert ledger.settings()["daily_minutes"] == 10


def test_resaving_the_same_queued_value_keeps_the_clock(ledger, clock):
    ledger.change_settings({"daily_minutes": 100})
    clock.now += 71 * HOUR
    ledger.change_settings({"daily_minutes": 100})
    clock.now += 2 * HOUR
    assert ledger.settings()["daily_minutes"] == 100


def test_cancel_and_bounds(ledger):
    ledger.change_settings({"daily_minutes": 100})
    ledger.cancel_change(ledger.pending_changes()[0]["id"])
    assert ledger.pending_changes() == []
    with pytest.raises(LedgerError):
        ledger.change_settings({"daily_minutes": -1})
    with pytest.raises(LedgerError):
        ledger.change_settings({"cooling_off_hours": 0})


# ---- history ----


def test_history_filters_by_text_and_date(ledger, clock):
    ledger.charge("a", 0, 10, "Rust async", "Tokio")
    clock.now += 24 * HOUR
    ledger.charge("b", 0, 10, "Cooking 100% pasta", "Chef")
    day_two = ledger.status() and ledger.search_watched()[0]["day"]

    ids = lambda **kw: [v["video_id"] for v in ledger.search_watched(**kw)]
    assert ids() == ["b", "a"]
    assert ids(query="rust") == ["a"]
    assert ids(query="tokio") == ["a"]
    assert ids(query="100%") == ["b"]
    assert ids(query="%") == ["b"]  # A literal percent sign, not a wildcard.
    assert ids(start_day=day_two) == ["b"]
    assert ids(end_day=day_two) == ["b", "a"]
    assert ids(limit=1, offset=1) == ["a"]


def test_history_route_rejects_bad_dates(client):
    assert client.get("/api/history/videos", params={"start": "'; drop"}).status_code == 400
    assert client.get("/api/history/videos", params={"q": "x"}).json() == {"videos": []}


# ---- playlists ----


def test_playlist_keeps_order_and_ignores_duplicates(config):
    store = Playlists(config.db_path)
    pid = store.create(" Later ")
    store.add(pid, [{"id": "b", "title": "B"}, {"id": "a", "title": "A", "channel": "c", "duration": 9}])
    store.add(pid, [{"id": "b", "title": "B again"}, {"id": "c", "title": "C"}])
    found = store.get(pid)
    assert found is not None
    assert found["name"] == "Later"
    assert [(i["id"], i["title"]) for i in found["items"]] == [("b", "B"), ("a", "A"), ("c", "C")]
    assert store.all()[0]["count"] == 3
    store.remove(pid, "a")
    kept = store.get(pid)
    assert kept is not None
    assert [i["id"] for i in kept["items"]] == ["b", "c"]
    store.delete(pid)
    assert store.get(pid) is None and store.all() == []


def test_playlist_needs_a_name_and_to_exist(config):
    store = Playlists(config.db_path)
    with pytest.raises(LedgerError):
        store.create("   ")
    with pytest.raises(LedgerError):
        store.add(99, [{"id": "a", "title": "A"}])


def test_playlist_routes(client):
    pid = client.post("/api/playlists", json={"name": "Later"}).json()["id"]
    item = {"id": "abcdefghijk", "title": "T"}
    assert client.post(f"/api/playlists/{pid}/items", json=item).status_code == 204
    assert client.post(f"/api/playlists/{pid}/items", json={**item, "id": "../bad"}).status_code == 400
    assert client.get(f"/api/playlists/{pid}").json()["items"][0]["id"] == "abcdefghijk"
    assert client.get("/api/playlists/999").status_code == 404
    assert client.post("/api/playlists", json={"name": " "}).status_code == 409


# ---- media ----


def build_sidx(version=0):
    refs = [(1000, 5000), (2000, 4000)]
    body = struct.pack(">BBBB", version, 0, 0, 0) + struct.pack(">II", 1, 1000)
    body += struct.pack(">II", 0, 0) if version == 0 else struct.pack(">QQ", 0, 0)
    body += struct.pack(">HH", 0, len(refs))
    for size, duration in refs:
        body += struct.pack(">III", size, duration, 0)
    return struct.pack(">I4s", len(body) + 8, b"sidx") + body


@pytest.mark.parametrize("version", [0, 1])
def test_parse_sidx(version):
    sidx = build_sidx(version)
    head = struct.pack(">I4s", 8, b"ftyp") + struct.pack(">I4s", 12, b"moov") + b"\0" * 4 + sidx
    located = locate_sidx(head)
    assert located is not None
    offset, size = located
    assert offset == 20
    index = parse_index(head, offset, size)
    assert index.init_end_byte == 19
    first, second = index.segments
    assert (first.start_byte, first.end_byte) == (20 + size, 20 + size + 999)
    assert (first.start_time, first.end_time) == (0, 5)
    assert second.start_byte == first.end_byte + 1
    assert (second.start_time, second.end_time) == (5, 9)


def test_media_playlist():
    text = media_playlist(SegmentIndex(10, (Segment(11, 20, 0, 5.5), Segment(21, 30, 5.5, 8))))
    assert '#EXT-X-MAP:URI="init.mp4"' in text
    assert "seg/1.m4s" in text
    assert "#EXT-X-TARGETDURATION:6" in text
    assert text.strip().endswith("#EXT-X-ENDLIST")


# ---- youtube ----


def fmt(format_id, **kw):
    return {"format_id": format_id, "protocol": "https", "url": f"https://x/{format_id}?expire=99999999999", **kw}


def test_stream_selection_uses_h264_to_1080_then_sdr_av1_to_2160():
    stream = stream_from_info(
        {
            "id": "abcdefghijk",
            "title": "t",
            "duration": 100,
            "description": "about this video",
            "formats": [
                fmt("136", height=720, fps=30, vcodec="avc1.4d401f", acodec="none", tbr=1000, width=1280),
                fmt("298", height=720, fps=60, vcodec="avc1.4d4020", acodec="none", tbr=2000, width=1280),
                fmt("303", height=1080, fps=60, vcodec="vp9", acodec="none", tbr=2000, width=1920),
                fmt("299", height=1080, fps=60, vcodec="avc1.64002a", acodec="none", tbr=3000, width=1920),
                fmt("399", height=1080, fps=60, vcodec="av01.0.09M.08", acodec="none", tbr=1500, width=1920),
                fmt("401", height=2160, fps=60, vcodec="av01.0.13M.08", acodec="none", tbr=9000, width=3840),
                fmt("701", height=2160, fps=60, vcodec="av01.0.13M.10", acodec="none", tbr=9500, width=3840, dynamic_range="HDR10"),
                fmt("315", height=2160, fps=60, vcodec="vp9", acodec="none", tbr=9000, width=3840),
                fmt("571", height=4320, fps=60, vcodec="av01.0.16M.08", acodec="none", tbr=20000, width=7680),
                fmt("140", vcodec="none", acodec="mp4a.40.2", tbr=130),
                fmt("140-drc", vcodec="none", acodec="mp4a.40.2", tbr=130),
                fmt("251", vcodec="none", acodec="opus", tbr=130),
            ],
        }
    )
    assert [t.format_id for t in stream.videos] == ["298", "299", "401"]
    assert stream.description == "about this video"
    assert stream.audio.format_id == "140"


def test_listings_drop_shorts_and_live():
    results = listings_from_info(
        {
            "entries": [
                {"id": "abcdefghijk", "url": "https://youtube.com/watch?v=abcdefghijk", "title": "ok", "duration": 300.0},
                {"id": "abcdefghij2", "url": "https://youtube.com/shorts/abcdefghij2", "title": "short"},
                {"id": "abcdefghij3", "url": "u", "title": "live", "live_status": "is_live"},
            ]
        }
    )
    assert [r.id for r in results] == ["abcdefghijk"]


def test_comments_keep_top_level_only():
    comments = comments_from_info(
        {
            "comments": [
                {"author": "@a", "text": "top", "parent": "root", "like_count": 5, "_time_text": "1 day ago"},
                {"author": "@b", "text": "reply", "parent": "abc"},
            ]
        }
    )
    assert [c["text"] for c in comments] == ["top"]
    assert comments[0]["likes"] == 5 and comments[0]["when"] == "1 day ago"


# ---- titles and frames ----


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("I BUILT A HOUSE IN 24 HOURS!!! 😱🔥", "I built a house in 24 hours"),
        ("Why NASA is great (MUST WATCH)", "Why NASA is great"),
        ("What happened??? #shorts #viral", "What happened?"),
        ("how the CPU works", "How the CPU works"),
        ("iPhone Duo Review", "iPhone Duo Review"),
        ("🔥🔥🔥", "🔥🔥🔥"),
    ],
)
def test_clean_title(raw, expected):
    assert clean_title(raw) == expected


def test_frames_use_generated_images_and_cache_them(tmp_path):
    seen = []

    def handler(request):
        seen.append(str(request.url))
        if "missing" in str(request.url):
            return httpx.Response(404)
        return httpx.Response(200, content=b"jpeg")

    async def main():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            frames = Frames(client, tmp_path)
            first = await frames.get("abcdefghijk")
            second = await frames.get("abcdefghijk")
            return first, second, await frames.get("missing0000")

    first, second, absent = asyncio.run(main())
    assert first == second and first.read_bytes() == b"jpeg"
    assert absent is None
    assert seen[0] == FRAME_URL.format(video_id="abcdefghijk") and len(seen) == 2


# ---- enforcement through HTTP ----


class FakeUpstream:
    async def aiter_bytes(self, _size):
        yield b"x" * 10

    async def aclose(self):
        pass


class FakeYouTube:
    def __init__(self):
        audio = Track("140", "mp4a.40.2", 128000)
        self._stream = Stream(
            "abcdefghijk", "TITLE", "chan", "UCx", 300, "desc", [Track("299", "avc1.64002a", 3000000, 1920, 1080, 60)],
            audio, {}, {}, 9e12,
        )
        self.opened = 0

    async def stream(self, video_id, refresh=False):
        return self._stream

    async def index(self, video_id, format_id):
        return SegmentIndex(9, (Segment(10, 19, 0, 300), Segment(20, 29, 300, 600), Segment(30, 39, 600, 900)))

    async def open_range(self, *args):
        self.opened += 1
        return FakeUpstream()


@pytest.fixture
def client(config, ledger):
    fake = FakeYouTube()
    with TestClient(create_app(config, ledger, cast(YouTube, fake))) as c:
        c.fake = fake
        yield c


def test_server_stops_delivering_when_budget_is_gone(client):
    base = "/media/abcdefghijk/299/seg"
    assert client.get(f"{base}/0.m4s").status_code == 200
    assert client.get(f"{base}/1.m4s").status_code == 200  # Crosses 10 minutes.
    blocked = client.get(f"{base}/2.m4s")
    assert blocked.status_code == 402
    assert blocked.json()["error"] == "out_of_time"
    assert client.fake.opened == 2
    assert client.get(f"{base}/0.m4s").status_code == 200  # Already paid for.
    assert client.get("/api/search", params={"q": "x"}).status_code == 402
    assert client.get("/media/abcdefghijk/master.m3u8").status_code == 402


def test_second_track_does_not_double_charge(client):
    client.get("/media/abcdefghijk/299/seg/0.m4s")
    client.get("/media/abcdefghijk/140/seg/0.m4s")
    assert client.get("/api/status").json()["used_seconds"] == 300


def test_comments_close_with_the_budget(client):
    client.get("/media/abcdefghijk/299/seg/0.m4s")
    client.get("/media/abcdefghijk/299/seg/1.m4s")
    assert client.get("/api/video/abcdefghijk/comments").status_code == 402


def test_master_playlist_route(client):
    text = client.get("/media/abcdefghijk/master.m3u8").text
    assert "299/index.m3u8" in text and 'URI="140/index.m3u8"' in text
    assert "1920x1080" in text
