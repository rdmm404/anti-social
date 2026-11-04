import asyncio
import sys
from pathlib import Path
from typing import Any, cast

import httpx
import pytest

from anti_social import ytdlp
from anti_social.youtube import ExtractionError, YouTube
from anti_social.ytdlp import ToolError, WorkerFailed, Workers, interpreter_of

# Speaks the worker protocol without yt-dlp or a network. The URL, last in argv, picks what happens.
FAKE = """
import json, os, sys, time
if os.environ.get("FAKE_NO_START"):
    sys.exit(1)
print(json.dumps({"ready": True}), flush=True)
for line in sys.stdin:
    request = json.loads(line)
    url = request["argv"][-1]
    if url == "die":
        os._exit(1)
    if url == "hang":
        time.sleep(30)
    if url == "fail":
        reply = {"id": request["id"], "error": "ERROR: [youtube] abc: Video unavailable"}
    else:
        reply = {"id": request["id"], "info": {"pid": os.getpid(), "argv": request["argv"]}}
    print(json.dumps(reply), flush=True)
"""


@pytest.fixture
def command(tmp_path: Path) -> list[str]:
    script = tmp_path / "fake_worker.py"
    script.write_text(FAKE)
    return [sys.executable, str(script)]


async def pids(workers: Workers, count: int) -> set[int]:
    infos = await asyncio.gather(*(workers.run(["x"]) for _ in range(count)))
    return {info["pid"] for info in infos}


def test_a_worker_answers_many_requests_without_restarting(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=1)
        first = await workers.run(["--flat-playlist", "url"])
        second = await workers.run(["url2"])
        assert first["argv"] == ["--flat-playlist", "url"]
        assert first["pid"] == second["pid"]
        workers.close()

    asyncio.run(scenario())


def test_requests_run_in_parallel_up_to_the_pool_size(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=2)
        assert len(await pids(workers, 6)) <= 2
        workers.close()

    asyncio.run(scenario())


def test_a_yt_dlp_failure_is_reported_and_the_worker_stays(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=1)
        before = (await workers.run(["x"]))["pid"]
        with pytest.raises(ToolError, match="Video unavailable"):
            await workers.run(["fail"])
        assert (await workers.run(["x"]))["pid"] == before
        workers.close()

    asyncio.run(scenario())


def test_a_crashed_worker_is_replaced(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=1)
        before = (await workers.run(["x"]))["pid"]
        with pytest.raises(WorkerFailed):
            await workers.run(["die"])
        assert (await workers.run(["x"]))["pid"] != before
        workers.close()

    asyncio.run(scenario())


def test_a_slow_request_times_out_and_the_worker_is_replaced(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=1)
        before = (await workers.run(["x"]))["pid"]
        with pytest.raises(TimeoutError):
            await workers.run(["hang"], timeout=0.3)
        # The late answer to "hang" must not be taken for the next request's.
        after = await workers.run(["y"])
        assert after["pid"] != before and after["argv"] == ["y"]
        workers.close()

    asyncio.run(scenario())


def test_a_cancelled_request_does_not_leave_its_answer_for_the_next(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=1)
        await workers.run(["x"])
        task = asyncio.create_task(workers.run(["hang"]))
        await asyncio.sleep(0.2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert (await workers.run(["y"]))["argv"] == ["y"]
        workers.close()

    asyncio.run(scenario())


def test_restart_replaces_every_worker(command: list[str]) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=2)
        before = await pids(workers, 4)
        await workers.restart()
        after = await pids(workers, 4)
        assert before.isdisjoint(after)
        workers.close()

    asyncio.run(scenario())


def test_a_worker_that_wont_start_is_not_retried_for_a_while(
    command: list[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    async def scenario() -> None:
        monkeypatch.setenv("FAKE_NO_START", "1")
        workers = Workers(command, size=1)
        with pytest.raises(WorkerFailed):
            await workers.run(["x"])
        monkeypatch.delenv("FAKE_NO_START")
        with pytest.raises(WorkerFailed):  # Still backing off, though it would work now.
            await workers.run(["x"])
        monkeypatch.setattr(ytdlp, "RETRY_AFTER", 0)
        workers._retry_at = 0
        assert (await workers.run(["x"]))["argv"] == ["x"]
        workers.close()

    asyncio.run(scenario())


def test_the_interpreter_comes_from_the_scripts_first_line(tmp_path: Path) -> None:
    script = tmp_path / "yt-dlp"
    script.write_text("#!/opt/ytdlp/bin/python\nprint('hi')\n")
    script.chmod(0o755)
    assert interpreter_of(str(script)) == ["/opt/ytdlp/bin/python"]
    script.write_text("#!/usr/bin/env python3\n")
    assert interpreter_of(str(script)) == ["/usr/bin/env", "python3"]
    script.write_bytes(b"\x7fELF\x02\x01")
    assert interpreter_of(str(script)) is None  # A compiled build has no Python to borrow.
    assert interpreter_of(str(tmp_path / "missing")) is None
    assert Workers.for_binary(str(script), size=2) is None
    script.write_text("#!/opt/ytdlp/bin/python\n")
    assert Workers.for_binary(str(script), size=0) is None


class OneOff(YouTube):
    def __init__(self, workers: Workers) -> None:
        super().__init__(cast(httpx.AsyncClient, None), workers=workers)
        self.once: list[tuple[str, ...]] = []

    async def _ytdlp_once(self, *args: str) -> dict[str, Any]:
        self.once.append(args)
        return {"from": "one-off"}


def test_youtube_uses_workers_and_falls_back_when_they_break(
    command: list[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    async def scenario() -> None:
        workers = Workers(command, size=1)
        youtube = OneOff(workers)
        assert (await youtube._ytdlp("url"))["argv"] == ["url"] and not youtube.once

        with pytest.raises(ExtractionError, match="Video unavailable") as failure:
            await youtube._ytdlp("fail")
        assert not str(failure.value).startswith("ERROR") and not youtube.once

        monkeypatch.setattr(ytdlp, "REQUEST_TIMEOUT", 0.3)
        with pytest.raises(ExtractionError, match="too long"):
            await youtube._ytdlp("hang")
        assert not youtube.once  # Slow is an answer, not a reason to ask again.

        workers._retry_at = float("inf")  # As if the workers can't start.
        assert await youtube._ytdlp("url") == {"from": "one-off"}
        workers.close()

    asyncio.run(scenario())
