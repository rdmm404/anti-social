# anti-social

A self-hosted, single-user YouTube frontend with a daily time limit the server enforces.
No feed, recommendations, autoplay, Shorts or comments. Thumbnails are frames YouTube
generates from the video, never the uploader's pick, and titles are stripped of shouting.

## Run

Needs `uv`, `yt-dlp` and `deno` (yt-dlp's JavaScript runtime).

```sh
uv run anti-social      # http://127.0.0.1:8484
uv run pytest
uv run mypy
```

Keep `yt-dlp` up to date. YouTube changes often, and an old version is the most likely
reason for playback to break.

## Deploy

```sh
git clone git@github.com:rdmm404/anti-social.git ~/apps/anti-social
cd ~/apps/anti-social
mkdir -p data
docker compose -f docker-compose.prd.yml up -d --build
```

This serves `yt.nautilus.voltr.org` through Traefik. The name resolves to a home network
address, so it's reachable from home and over Tailscale, and there's no login: don't add
it to a public tunnel. The container keeps its own copy of `yt-dlp` in `data/bin` and
updates it daily.

| Variable | Default | Purpose |
| --- | --- | --- |
| `ANTI_SOCIAL_HOST` / `ANTI_SOCIAL_PORT` | `127.0.0.1` / `8484` | Listen address. There is no login, so don't expose it. |
| `ANTI_SOCIAL_DATA` | `data` | SQLite database and thumbnail cache |
| `ANTI_SOCIAL_YTDLP_SELF_UPDATE` | off | Update yt-dlp daily. Needs its release build. |
| `ANTI_SOCIAL_DAY_START` | `4` | Local hour when the daily budget resets |
| `ANTI_SOCIAL_COOLING_OFF_HOURS` | `72` | Wait before a looser limit applies |
| `ANTI_SOCIAL_DAILY_MINUTES` | `60` | First-run seed only |
| `ANTI_SOCIAL_UNLOCKS_PER_DAY` | `2` | First-run seed only |
| `ANTI_SOCIAL_UNLOCK_MINUTES` | `30` | First-run seed only |
| `ANTI_SOCIAL_UNLOCK_WAIT_SECONDS` | `60` | First-run seed only |

## How the limit works

- All video passes through the server as HLS segments, and `/media/.../seg/N.m4s` is the
  enforcement point. When time runs out it returns `402` and sends no more video.
- The budget is charged for media delivered, so screen-off playback counts. Each second of
  a video is charged once per budget day, so audio and video tracks, quality switches,
  reloads and a second device don't double-charge.
- Charges happen on delivery, so roughly 20-30 seconds of buffered video is paid for ahead
  of playback. Speed doesn't change the cost: 10 minutes of video costs 10 minutes at 2x.
- Unlocks only open once the day's budget is spent. During one, watching isn't charged.
- Tighter limits apply immediately. Looser ones wait out the cooling-off period and can be
  cancelled. Saving a different value restarts the wait.

## Layout

| File | Responsibility |
| --- | --- |
| `ledger.py` | Budget, unlocks, cooling-off, subscriptions. Knows nothing about YouTube, so other platforms can share the budget. |
| `youtube.py` | All yt-dlp and YouTube knowledge |
| `media.py` | Parses `sidx` indexes and writes HLS playlists |
| `playlists.py` | Saved video lists |
| `branding.py` | Title cleanup and generated-frame thumbnails |
| `main.py` | HTTP routes |
| `static/` | Frontend, with hls.js and JetBrains Mono vendored |

## Later

- Block YouTube domains in AdGuard Home, with an exception for this server
- A curated feed of useful videos, in the spirit of hackertube.org
