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
it to a public tunnel. The container keeps its own `yt-dlp` in `data/ytdlp` and updates
it daily.

| Variable | Default | Purpose |
| --- | --- | --- |
| `ANTI_SOCIAL_HOST` / `ANTI_SOCIAL_PORT` | `127.0.0.1` / `8484` | Listen address. There is no login, so don't expose it. |
| `ANTI_SOCIAL_DATA` | `data` | SQLite database and thumbnail cache |
| `ANTI_SOCIAL_YTDLP_UPDATE` | none | Command run daily to update yt-dlp. The Docker image sets this. |
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
- Unlocks only open once the day's budget is spent. After the wait, one adds its minutes
  to today's budget. They last until they're watched or the day resets, and pausing doesn't
  use them.
- Tighter limits apply immediately. Looser ones wait out the cooling-off period and can be
  cancelled. Saving a different value restarts the wait.

## Opening YouTube links

Replace a YouTube link's domain with this server's and it opens here:
`https://yt.nautilus.voltr.org/watch?v=ID`. `/shorts/`, `/live/`, `/embed/`, `/@handle`,
`/channel/`, `/results` and `/playlist` work too, as does the path from a `youtu.be` link.
Pasting a link into the search box does the same.

- **Android:** install the app from Chrome, then share a link to `anti-social` from any app.
- **Desktop:** in a redirect extension such as Redirector, send
  `^https?://(?:www\.|m\.)?youtube\.com/(.*)` and `^https?://youtu\.be/(.*)` to
  `https://yt.nautilus.voltr.org/$1`.

## Fire TV app

`tv/` is a small native app for Android-based Fire TVs. It uses the same API and spends the
same budget, and all the rules stay on the server. It has search, channels, playlists,
history, the player and unlocks. Limits are still changed on the web. Fire TVs on Vega OS
can't sideload it.

```sh
cd tv
./gradlew testDebugUnitTest
./gradlew assembleRelease   # -PantiSocialServer=http://10.0.2.2:8484 for another server
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

It needs JDK 21 and the Android SDK, with `sdk.dir` in `tv/local.properties`. Releases are
signed with the machine's debug key, so installing from another machine means uninstalling
first.

## Layout

| File | Responsibility |
| --- | --- |
| `ledger.py` | Budget, unlocks, cooling-off, subscriptions. Knows nothing about YouTube, so other platforms can share the budget. |
| `youtube.py` | All yt-dlp and YouTube knowledge |
| `media.py` | Parses `sidx` indexes and writes HLS playlists |
| `playlists.py` | Saved video lists |
| `branding.py` | Title cleanup and generated-frame thumbnails |
| `main.py` | HTTP routes |
| `tv/` | The Fire TV app |
| `static/` | Frontend, with hls.js, JetBrains Mono and a Lucide icon sprite vendored |

## Later

- Block YouTube domains in AdGuard Home, with an exception for this server
- A curated feed of useful videos, in the spirit of hackertube.org
