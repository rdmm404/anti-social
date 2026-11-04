"""A small pool of long-lived yt-dlp processes. See ytdlp_worker.py for what runs in them.

Each worker handles one request at a time, so the pool size is how many run in parallel.
"""

import asyncio
import json
import shlex
import shutil
import time
from pathlib import Path
from typing import Any

WORKER_SCRIPT = Path(__file__).with_name("ytdlp_worker.py")
START_TIMEOUT = 30
REQUEST_TIMEOUT = 90
# A worker is replaced after this many requests, so a leak can't build up forever.
MAX_REQUESTS = 300
# After a worker fails to start, don't try again for a while: callers use one-off processes.
RETRY_AFTER = 300
# Replies carry a video's whole format list, which is far past asyncio's default line limit.
LINE_LIMIT = 64 * 1024 * 1024


class ToolError(Exception):
    """yt-dlp ran and reported a failure. The text is its own."""


class WorkerFailed(Exception):
    """The worker itself broke, so the request never reached yt-dlp or its answer was lost."""


def interpreter_of(ytdlp_bin: str) -> list[str] | None:
    """The command that runs yt-dlp's own Python, read from its script's first line."""
    path = shutil.which(ytdlp_bin)
    if path is None:
        return None
    try:
        with open(path, "rb") as script:
            first = script.readline().decode(errors="replace").strip()
    except OSError:
        return None
    if not first.startswith("#!"):
        return None  # A single-file build has no Python of its own to borrow.
    return shlex.split(first[2:]) or None


class Worker:
    def __init__(self, command: list[str], generation: int):
        self._command = command
        self.generation = generation
        self._process: asyncio.subprocess.Process | None = None
        self._ids = 0
        self.served = 0
        self.broken = False

    @property
    def started(self) -> bool:
        return self._process is not None

    async def start(self) -> None:
        try:
            self._process = await asyncio.create_subprocess_exec(
                *self._command,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                limit=LINE_LIMIT,
            )
            assert self._process.stdout is not None
            line = await asyncio.wait_for(self._process.stdout.readline(), START_TIMEOUT)
            if json.loads(line or b"{}").get("ready") is not True:
                raise WorkerFailed("yt-dlp worker did not start")
        except (OSError, ValueError, TimeoutError, WorkerFailed) as error:
            self.kill()
            raise WorkerFailed(str(error) or "yt-dlp worker did not start") from error

    def kill(self) -> None:
        self.broken = True
        if self._process and self._process.returncode is None:
            self._process.kill()

    async def request(self, argv: list[str], timeout: float) -> dict[str, Any]:
        """Any exit other than a normal reply leaves an answer in flight, so the worker is dropped."""
        assert self._process and self._process.stdin and self._process.stdout
        self._ids += 1
        self.served += 1
        done = False
        try:
            self._process.stdin.write(json.dumps({"id": self._ids, "argv": argv}).encode() + b"\n")
            await self._process.stdin.drain()
            line = await asyncio.wait_for(self._process.stdout.readline(), timeout)
            if not line:
                raise WorkerFailed("yt-dlp worker exited")
            reply: dict[str, Any] = json.loads(line)
            if reply.get("id") != self._ids:
                raise WorkerFailed("yt-dlp worker answered out of turn")
            done = True
        except TimeoutError:
            raise  # A TimeoutError is also an OSError, but it means yt-dlp was slow, not broken.
        except (OSError, ValueError) as error:  # Broken pipe, or a reply that isn't JSON.
            raise WorkerFailed(str(error)) from error
        finally:
            if not done:
                self.kill()
        if "error" in reply:
            raise ToolError(reply["error"])
        info: dict[str, Any] = reply["info"]
        return info


class Workers:
    def __init__(self, command: list[str], size: int = 2):
        self._command = command
        self._generation = 0
        self._idle: asyncio.Queue[Worker] = asyncio.Queue()
        self._retry_at = 0.0
        for _ in range(size):
            self._idle.put_nowait(Worker(command, self._generation))

    @classmethod
    def for_binary(cls, ytdlp_bin: str, size: int) -> "Workers | None":
        interpreter = interpreter_of(ytdlp_bin) if size > 0 else None
        if interpreter is None:
            return None
        return cls([*interpreter, "-I", str(WORKER_SCRIPT)], size)

    def _fresh(self) -> Worker:
        return Worker(self._command, self._generation)

    async def run(self, argv: list[str], timeout: float | None = None) -> dict[str, Any]:
        """Raises ToolError for a yt-dlp failure, WorkerFailed if the pool can't be used,
        and TimeoutError if yt-dlp was too slow."""
        timeout = timeout or REQUEST_TIMEOUT
        if time.monotonic() < self._retry_at:
            raise WorkerFailed("yt-dlp workers are unavailable")
        worker = await self._idle.get()
        try:
            if not worker.started:
                try:
                    await worker.start()
                except WorkerFailed:
                    self._retry_at = time.monotonic() + RETRY_AFTER
                    raise
            return await worker.request(argv, timeout)
        finally:
            self._give_back(worker)

    def _give_back(self, worker: Worker) -> None:
        if worker.broken or worker.served >= MAX_REQUESTS or worker.generation != self._generation:
            worker.kill()
            worker = self._fresh()
        self._idle.put_nowait(worker)

    async def restart(self) -> None:
        """Replace every worker, so a freshly updated yt-dlp is the one answering.

        Also used at startup to have the workers ready before the first request.
        Busy workers are replaced as they finish.
        """
        self._generation += 1
        idle = []
        while not self._idle.empty():
            idle.append(self._idle.get_nowait())
        replacements = [self._fresh() for _ in idle]
        for worker in idle:
            worker.kill()
        results = await asyncio.gather(*(w.start() for w in replacements), return_exceptions=True)
        if any(isinstance(result, WorkerFailed) for result in results):
            self._retry_at = time.monotonic() + RETRY_AFTER
        for worker in replacements:
            self._idle.put_nowait(self._fresh() if worker.broken else worker)

    def close(self) -> None:
        while not self._idle.empty():
            self._idle.get_nowait().kill()
