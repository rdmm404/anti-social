# Plan: the hosted version

How to get from the self-hosted server to the design in ADRs 0001-0004. The first milestones serve one user, me, and multi-user comes only after the core has been lived with. The Python server keeps running until every client has moved off it.

## 1. Single-user slice: Go server and TV app

**Server**, a new `server/` module in Go:

- Port the ledger: budget days, interval merging so each second is charged once, sessions, slots, samples, settings with cooling-off. `src/anti_social/ledger.py` and `tests/test_core.py` are the spec: turn the tests into Go tests first, and change only what ADR 0001 changes (charging reported, not delivered, seconds).
- Check-ins with a server-set expiry.
- Every request carries a device token. One account: its first device is a static token in config, which signs in the settings page; other devices pair by showing a code that the settings page approves (ADR 0004), and get tokens of their own. Multi-user later adds account creation; devices and pairing already exist.
- Settings: an API to read and change them (daily minutes, session gap, SponsorBlock categories), with cooling-off for looser values. A small settings page served by the server, using `html/template` and a little JS, signed into once with the token and remembered in a cookie.
- Postgres from the start, run with Docker Compose in the homelab, so going public later is a connection string, not a migration.

**TV app:**

- Split `tv/` into Gradle modules: `core` (Kotlin Multiplatform: API client, check-ins, session and budget state, SponsorBlock, screen state, generated thumbnails and de-shouted titles), `youtube` (NewPipeExtractor), `playback` (Media3) and `tv` (screens only).
- On-device extraction plus check-ins against the Go server. The app refuses to start a video without a successful check-in.
- Fire TV blocking, best effort: find out what Fire OS allows for Amazon's YouTube app (adb disable, uninstall) and whether an accessibility service can catch it and Silk.

Done when the Go server runs in the homelab, the Fire TV works against it, and YouTube on the Fire TV is blocked as far as Fire OS allows.

## 2. Phone app and updates

- A `phone` module with touch screens on the same `core`, `youtube` and `playback`.
- Blocking from ADR 0002: link handling, the accessibility service, and a setup screen that walks through the adb disable and Open by default.
- An in-app updater in shared code for phone and TV: check GitHub Releases, offer to install. CI builds both APKs.

Done when the phone app installs easily, connects to the Go server, works as well as the TV app, and blocks YouTube.

## 3. Desktop extension

- The youtube.com extension from ADR 0003 for Chromium, in TypeScript, with its own check-in and SponsorBlock code, using the same token.

Done when the extension works in a desktop Chromium browser, including the budget and sessions.

## 4. Validation and hardening

- A week or so of daily use: note every imperfection, try to break it (offline, airplane mode, two devices, killing the app mid-video, clearing data), fix as I go.
- Stop the Python server, delete `src/anti_social/`, and move the README to the new setup.

## 5. Public deployment, still single user

- Move the server to Cloud Run so nothing depends on Tailscale.
- Neon Postgres on the free tier, in the same region as the server. Check the free tier's compute-hour limits against check-ins every 15 seconds while something plays.
- Rate limits and Cloudflare in front.
- Everything in Terraform, in `infra/`: Cloud Run, Artifact Registry, Secret Manager, service accounts, Workload Identity Federation for GitHub Actions, Neon and Cloudflare DNS. State in a GCS bucket created by a small bootstrap. CI deploys new images with `gcloud run deploy`, and Terraform ignores image changes so the two don't fight.

## 6. Multi-user and go-live prep

- Account creation and recovery from ADR 0004 (devices and pairing came in milestone 1), then mark it accepted or revise it.
- Decide how long the server keeps readable watch history. ADR 0004 says it never holds one, but since milestone 1 it keeps titles per day for the History tab.
- Move my own data into a real account.
- Self-hosting docs, the SponsorBlock credit, and whether the extension goes in the Chrome Web Store.

## 7. Share it

## Someday: other platforms

The long-term aim: one app for using every platform on purpose, not just YouTube. Not scheduled yet. Reddit, Instagram, Facebook and X would be restyled websites, not clients of our own: X sent Nitter a cease and desist in August 2026, Meta has no API for reading your feed and goes after scrapers, and Reddit's logged-in website gives voting, commenting and subscriptions without needing an API key.

- **One mechanism:** per-site rules for what to hide and what counts as a post, injected by the desktop extension and, on Android, by a WebView screen in our app (Chrome on Android has no extensions). The accessibility service blocks the official apps and the sites in browsers, and sends links to the app. The user logs in to their real account in the WebView.
- **What each site keeps:** Instagram and Facebook open their Favorites or friends feeds, keep stories, DMs, events and birthdays, and hide Reels, Explore, suggested posts and Marketplace. X opens Following and hides For You, Explore, trends and Grok. Reddit keeps subscriptions and hides r/popular, r/all, recommendations and app nags. Desktop Reddit can use old.reddit.com, which changes less often.
- **Budget:** "N things per day" plus an optional time cap. A session asks how many things you want, as it already does for videos. A post counts once it's mostly on screen for about a second, once per budget day. Opening a thread or a carousel is one post. A feed page where the rules find no posts is treated as broken and blocked, so a stale selector can't silently turn the budget off.
- **Risks:** selectors break whenever a site changes. Meta and X can detect WebViews and make logging in harder. Add X last, since it has shown it will send lawyers, and only ever hide things on its page.
- **The ledger:** don't hardcode "video" into the Go ledger more than milestone 1 needs, so "posts" can be added later.

## Alongside

- Design tokens in one JSON file, generated into CSS variables and a Kotlin theme. Lucide icons and JetBrains Mono from the same sources everywhere. The settings page and the extension both use the CSS side.
- A short catalog of shared components: video row, channel header, budget meter, session gate.
- The session gate replaces today's full-page `gatePanel()`. The video page stays, its generated frame blurred and dimmed behind a dialog, with title and channel still visible. With no session open: "X minutes left today. Start a session?" and the 1-3 picker, which then plays this video. Between sessions: when the next one opens. Out of time: when the day resets. Mid-playback it pauses and blurs the current frame, and resumes in place once a session opens. A video that already took up a slot today shows no gate. Same wording on every client, kept with the design tokens. Built for the TV in milestone 1.
