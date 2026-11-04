"""A long-lived yt-dlp, so each request skips Python and yt-dlp's start-up (0.6s on the server).

This runs under yt-dlp's own interpreter, not the app's, so it may import nothing but the
standard library and yt_dlp. It reads one JSON request per line on stdin and answers with
one JSON line on stdout:

    {"id": 1, "argv": ["--flat-playlist", "<url>"]}  ->  {"id": 1, "info": {...}}
                                                         {"id": 1, "error": "ERROR: ..."}

`argv` is yt-dlp's command line, with the URL last.
"""

import json
import os
import sys

BASE_ARGV = ["--ignore-config", "--no-warnings", "--color", "never"]


class Silent:
    """Failures reach the caller as an error reply, so yt-dlp has nothing to print."""

    def debug(self, message: str) -> None: ...
    def warning(self, message: str) -> None: ...
    def error(self, message: str) -> None: ...


def main() -> None:
    # The protocol owns stdout. Anything else that writes to it would corrupt a reply.
    reply_to = os.fdopen(os.dup(1), "w", buffering=1, encoding="utf-8")
    sys.stdout = sys.stderr

    import yt_dlp

    # One YoutubeDL per set of options keeps its connections and caches for the next request.
    sessions: dict[tuple[str, ...], yt_dlp.YoutubeDL] = {}

    def session(options: list[str]) -> yt_dlp.YoutubeDL:
        key = tuple(options)
        if key not in sessions:
            params = yt_dlp.parse_options([*BASE_ARGV, *options]).ydl_opts
            sessions[key] = yt_dlp.YoutubeDL({**params, "logger": Silent()})
        return sessions[key]

    reply_to.write(json.dumps({"ready": True}) + "\n")
    for line in sys.stdin:
        request = json.loads(line)
        try:
            *options, url = request["argv"]
            ydl = session(options)
            info = ydl.sanitize_info(ydl.extract_info(url, download=False))
            reply = {"id": request["id"], "info": info}
            text = json.dumps(reply)
        except Exception as error:
            text = json.dumps({"id": request["id"], "error": str(error)})
        reply_to.write(text + "\n")


if __name__ == "__main__":
    main()
