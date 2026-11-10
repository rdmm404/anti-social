# anti-social

A self-hosted, single-user YouTube frontend with sessions and a daily time limit, both
enforced by the server.
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
| `ANTI_SOCIAL_YTDLP_WORKERS` | `2` | Long-lived yt-dlp processes, which is how many YouTube requests run at once. `0` starts a process per request. |
| `ANTI_SOCIAL_YTDLP_UPDATE` | none | Command run daily to update yt-dlp. The Docker image sets this. |
| `ANTI_SOCIAL_DAY_START` | `4` | Local hour when the daily budget resets |
| `ANTI_SOCIAL_COOLING_OFF_HOURS` | `72` | Wait before a looser limit applies |
| `ANTI_SOCIAL_DAILY_MINUTES` | `120` | First-run seed only |
| `ANTI_SOCIAL_SESSION_GAP_MINUTES` | `20` | First-run seed only |

## How the limit works

- All video passes through the server as HLS segments, and `/media/.../seg/N.m4s` is the
  enforcement point. When time runs out it returns `402` and sends no more video.
- The budget is charged for media delivered, so screen-off playback counts. Each second of
  a video is charged once per budget day, so audio and video tracks, quality switches,
  reloads and a second device don't double-charge.
- Charges happen on delivery, so roughly 20-30 seconds of buffered video is paid for ahead
  of playback. Speed doesn't change the cost: 10 minutes of video costs 10 minutes at 2x.
- Tighter limits apply immediately. Looser ones wait out the cooling-off period and can be
  cancelled. Saving a different value restarts the wait.

## How sessions work

- With no session open, the app asks how many videos you want, from 1 to 3. Search, channels
  and playlists stay closed until you answer, and the server refuses video and search.
- A video takes up one of those slots once it's been delivered for 30 seconds. Leaving before
  that is free up to 3 times per session. After that, a video takes a slot when it starts.
- When every slot is taken, browsing closes and the session is over. The videos you started
  can still finish.
- The next session opens 20 minutes after the last video delivered, and that gap is a setting.
- A session's unused videos expire after 30 minutes with nothing delivered.
- A video that took up a slot today can be resumed at any time without using another one. It
  still counts against the daily limit.

## Opening YouTube links

Replace a YouTube link's domain with this server's and it opens here:
`https://yt.nautilus.voltr.org/watch?v=ID`. `/shorts/`, `/live/`, `/embed/`, `/@handle`,
`/channel/`, `/results` and `/playlist` work too, as does the path from a `youtu.be` link.
Pasting a link into the search box does the same.

- **Android:** install the app from Chrome, then share a link to `anti-social` from any app.
- **Desktop:** in a redirect extension such as Redirector, send
  `^https?://(?:www\.|m\.)?youtube\.com/(.*)` and `^https?://youtu\.be/(.*)` to
  `https://yt.nautilus.voltr.org/$1`.

## Go server

`server/` replaces this Python server for clients that play YouTube themselves (ADR 0001). It
keeps the budget, sessions, settings, subscriptions, playlists and history in Postgres. Clients
check in about every 15 seconds while a video plays. Each check-in is charged, and its answer
says whether to go on for another minute. The Python server keeps running until every client
has moved over (see `docs/plan-hosted.md`).

Tools come from `mise.toml`: Go, dbmate and Java. `.env` holds the owner's token
(`ANTI_SOCIAL_TOKEN`, made with `openssl rand -hex 32`) and is never committed.

That token is the account's first device, and it signs in the settings page. Every other
device pairs (ADR 0004): it shows a code and a QR code linking to `/pair?code=...`, you
approve it there while signed in, and it gets a token of its own. The settings page lists
devices, and removing one signs it out. A browser that signs in is a device too, with its own
token in the cookie, so signing out revokes it.

```sh
mise run db       # Postgres 18 in Podman or Docker, on 127.0.0.1:55432
mise run test     # Go tests, against memory and Postgres
mise run server   # http://localhost:8080, settings page at /
```

Each domain is a package holding its rules and the interface for its storage, with adapters
below it. Adapters import the domain and the domain imports none of them, so the rules never
see SQL or HTTP:

| Package | Responsibility |
| --- | --- |
| `budget` | Budget days, sessions, check-ins, limits with cooling-off, history |
| `library` | Subscriptions and playlists |
| `accounts` | Devices, their tokens, and pairing |
| `<domain>/postgres` | Its storage in Postgres, with raw pgx queries returning the domain's types |
| `<domain>/memory` | The same storage in memory, for tests |
| `<domain>/api` | Its JSON API: wire types, handlers, and mapping to and from the domain's types |
| `apperr` | The one error type for what a client did or asked: invalid, refused, not found, unauthorized |
| `httpapi` | What every API shares: reading requests, writing answers and errors |
| `web` | The settings page |
| `cmd/anti-social` | The only place that wires everything together |

The domain tests run against the memory adapters, and against Postgres too when
`ANTI_SOCIAL_TEST_DATABASE_URL` is set, so the two can't drift apart. `mise run test` sets it.
Without it, `go test ./...` needs no database and the Postgres halves skip.

Migrations are dbmate files in `server/db/migrations/`. The server applies them on start. Make
one with `cd server && dbmate new <name>`, apply it with `dbmate up`, then run
`mise run db:schema` to refresh `server/db/schema.sql`. Once a migration has reached a shared
database, fix it with a new migration instead of editing it.

| Variable | Default | Purpose |
| --- | --- | --- |
| `ANTI_SOCIAL_DATABASE_URL` | required | Postgres connection string |
| `ANTI_SOCIAL_TOKEN` | required | The owner's token, at least 32 characters. Changing it signs out whoever used the old one; paired devices stay. |
| `ANTI_SOCIAL_ADDR` | `:8080` | Listen address |
| `TZ` | system | Zone the budget day is counted in |
| `ANTI_SOCIAL_DAY_START` | `4` | Local hour when the daily budget resets |
| `ANTI_SOCIAL_COOLING_OFF_HOURS` | `72` | Wait before a looser limit applies |
| `ANTI_SOCIAL_DAILY_MINUTES` | `120` | Starting value |
| `ANTI_SOCIAL_SESSION_GAP_MINUTES` | `20` | Starting value |

## Fire TV app

`tv/` is a native app for Android-based Fire TVs, and it talks to the Go server. It gets video
from YouTube itself with NewPipeExtractor and plays it with Media3. It checks in before a
video starts and while it plays, and stops when the server says so or can't be reached for a
minute. It has search, channels, playlists, history and the player. Limits are changed on the
server's settings page. Fire TVs on Vega OS can't sideload it.

| Module | Responsibility |
| --- | --- |
| `core` | Kotlin Multiplatform: the server API, check-ins, budget state, title cleanup, generated thumbnails, DASH manifests. No Android or YouTube. |
| `youtube` | NewPipeExtractor, behind core's `YouTube` interface |
| `playback` | Media3 |
| `tv` | The screens |

```sh
cd tv
./gradlew :core:jvmTest :youtube:test testDebugUnitTest
./gradlew :youtube:test -Plive           # also checks extraction against YouTube itself
./gradlew assembleRelease                # -PantiSocialServer=http://10.0.2.2:8080 for the emulator
adb connect <tv-ip>:5555
adb install -r tv/build/outputs/apk/release/tv-release.apk
```

On first start the app shows a pairing code and a QR code. Scan it with a phone signed in to
the settings page, or open `/pair` there and type the code, and approve. The TV signs in by
itself a few seconds later. Debug builds can carry a token instead, with
`-PantiSocialToken=...`. Release builds never do, because they're published.

It needs JDK 21 and the Android SDK (`ANDROID_HOME`, which `mise.toml` sets). Local builds are
signed with the machine's debug key, so installing a release over one means uninstalling first.

Releases are built by `.github/workflows/tv-release.yml`. Push a tag like `tv-v1.2.0` and it
tests, builds, and attaches `anti-social-tv.apk` to a GitHub release. It signs with a
keystore kept in the repo secrets `TV_KEYSTORE_BASE64` and `TV_KEYSTORE_PASSWORD` (alias
`anti-social`), and every release must use that same key or the TV refuses the update. Keep
a copy of the keystore somewhere safe. To make one:

```sh
keytool -genkeypair -alias anti-social -keyalg RSA -keysize 2048 -validity 10000 \
  -keystore release.jks
base64 -w0 release.jks | gh secret set TV_KEYSTORE_BASE64
gh secret set TV_KEYSTORE_PASSWORD
```

To update the TV without adb, open Downloader (free on the Amazon Appstore) and enter the code
`4875990`. It is an aftv.news short link to
`https://github.com/rdmm404/anti-social/releases/latest/download/anti-social-tv.apk`, so it
always fetches the newest release. Downloader needs "Install unknown apps" allowed under
Settings → My Fire TV → Developer Options. A build signed with another key, such as a local
one, has to be uninstalled first.

## Layout

| File | Responsibility |
| --- | --- |
| `ledger.py` | Budget, sessions, cooling-off, subscriptions. Knows nothing about YouTube, so other platforms can share the budget. |
| `youtube.py` | All yt-dlp and YouTube knowledge |
| `media.py` | Parses `sidx` indexes and writes HLS playlists |
| `playlists.py` | Saved video lists |
| `branding.py` | Title cleanup and generated-frame thumbnails |
| `main.py` | HTTP routes |
| `server/` | The Go server |
| `tv/` | The Fire TV app |
| `static/` | Frontend, with hls.js, JetBrains Mono and a Lucide icon sprite vendored |

## Later

- Block YouTube domains in AdGuard Home, with an exception for this server
- A curated feed of useful videos, in the spirit of hackertube.org
