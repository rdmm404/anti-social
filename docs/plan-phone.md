# Plan: the phone app (milestone 2)

Milestone 2 of `docs/plan-hosted.md`, broken into slices for whoever builds it. Each slice ends with something installed on the phone that works end to end. Build them in order and commit each one when its criterion holds.

**Done when** the app is installed on the owner's OnePlus 12 (Android 16), is paired with the Go server, and does everything the Fire TV app does, plus what the v1 web app did (slices 5 and 6).

## Read first

- `docs/adr/0001` to `0004`: the server keeps the budget, clients play YouTube themselves, devices pair.
- `README.md`, sections "Go server" and "Fire TV app".
- `tv/`: the `core`, `youtube` and `playback` modules are shared. `tv/tv/` is the TV's screens, and it's the reference for every phone screen.
- `src/anti_social/static/app.js`: the v1 web app. It's the reference for the features the TV lacks: the description, comments, playlist editing and import, and pasting a link into search.

## Settled decisions

The owner made these calls; build to them.

- **Screens** are the TV's: search, channels, playlists, history and the player, with the same session gate (every tab except history is gated until a session is open). Touch and portrait instead of a remote.
- **The player** goes full screen on rotate, keeps playing with the screen locked or the app in the background, and does picture-in-picture.
- **Links** to youtube.com, m.youtube.com, youtu.be and `/shorts/` open in the app, from a tap or from Share. A Short plays as a regular video. Every video needs an open session, links included.
- **Pairing** shows no code. The app starts a pairing, opens the approval page in a Custom Tab, and claims its token the way the TV does.
- **Releases** go out on `phone-v*` tags, apart from `tv-v*`, signed with the same keystore.
- **Not in this milestone:** blocking YouTube (Open by default, the accessibility service, the adb-disable setup screen), the in-app updater and SponsorBlock. They're milestone 4. The phone already has YouTube disabled, and another app redirects its links.

## Architecture

- **New module `tv/phone`** (Gradle `:phone`), application ID `org.voltr.antisocial.phone`, minSdk 31. Copy the build setup from `tv/tv/build.gradle.kts`: `antiSocialServer` and `antiSocialToken`, cleartext only for `http://`, desugaring for NewPipe, and release signing from `ANTI_SOCIAL_KEYSTORE`.
- **YouTube data** comes through core's `YouTube` interface, implemented in `youtube/NewPipeYouTube.kt`. Every new kind of data (metadata, description, comments, playlists) widens that interface and its data classes rather than calling NewPipe from screens. Keep the interface free of Android, since `core` is Kotlin Multiplatform.
- **Server calls** go through core's `Api`. The Go server already has every route this milestone needs, including playlist create, delete, add item and remove item, so no server change should be necessary. If one turns out to be, stop and ask.
- **Check-ins must outlive the screen.** In the TV app, `VideoPlayer` in `tv/tv/.../PlayerScreen.kt` owns the ExoPlayer, the `Watch`, the position polling and the saved resume positions, all inside a composable. Background playback can't work that way. Move that loop into something a Media3 `MediaSessionService` owns: polling, `WatchedStretches`, check-ins on `watch.due()`, pausing when `!watch.mayPlay()`, and the final check-in on stop. Put the Android-free parts in `core` and the Media3 parts in `playback`. Screens then only observe it. The TV can move to it later. Leave TV behaviour unchanged in this milestone, and keep its tests green.
- **Design:** load the `ui-taste` skill before building screens. Start from the colours and type in `tv/tv/.../Ui.kt`. Sharing design tokens between apps is a separate plan item, so copying them is fine for now.

## Slices

### 1. Skeleton and release

- The `:phone` module with an empty screen that shows the server URL.
- `.github/workflows/phone-release.yml`, mirroring `tv-release.yml`: tag `phone-v*`, asset `anti-social-phone.apk`, the same keystore secrets.

Done when a release build installs on the phone over adb and opens.

### 2. Pairing, the gate, search and playing

- Pairing: `api.startPairing(Build.MODEL)`, open `api.pairUrl(code)` in a Custom Tab (`androidx.browser`), and poll `claimPairing` as `SignIn.kt` does. The activity is paused while the tab is showing, so poll from `Services.scope`, not a lifecycle-bound scope. Once the token arrives, bring the app back to the front.
- The session gate and the 1-3 picker, search, and a foreground player with check-ins. This one can still live in the screen; slice 3 moves it.

Done when the phone pairs through the Custom Tab, starts a session, plays a search result, and the server logs its check-ins.

### 3. Playback in a service

- The `MediaSessionService` from Architecture, with its media notification.
- Full screen on rotate, and picture-in-picture when leaving the app during playback.
- Resume positions, speed and quality, as on the TV.

Done when check-ins keep arriving with the screen locked and in picture-in-picture, and a refused check-in (session over) stops playback in both.

### 4. Browsing as on the TV, and links

- Channels with subscribe and unsubscribe, playlists, playlist pages with "next" at the end of a video, and history.
- Intent filters for youtube.com, m.youtube.com and youtu.be links, and `ACTION_SEND` text for Share. Pasting a link into search opens it. Parse links in `core`, porting the rules from `src/anti_social/youtube.py` (`youtu.be/<id>`, `/shorts/`, `/live/`, `/embed/`, `/v/`, `watch?v=`, and `t=` as the start position), with tests.

Done when every TV screen works on the phone, and a Shorts link shared from another app plays behind the session gate.

### 5. The video page and metadata

- Upload date and view count on videos in lists, and likes too on the video page (NewPipe only gives likes there). Add them to `Video`, `VideoDetails` and `NewPipeYouTube`. Lists that come from the server (history and playlists) don't carry them, so show them when they're present.
- The description, with links that open and timestamps that seek (`linkified` in `app.js`).
- Comments, loaded when opened (NewPipe's `CommentsInfo`).

Done when the video page shows the description, comments and likes, and a timestamp in the description seeks.

### 6. Playlist editing, import and settings

- Add to a playlist from the player, creating one if needed. Remove an item, delete a playlist. Add these calls to `Api`.
- Import a YouTube playlist from a link: read it on the device through a new `YouTube.playlist(url)`, then create it and add its items through the API.
- A Settings entry that opens the server's settings page in a Custom Tab.

Done when a playlist imported from a YouTube link shows up on both the phone and the TV.

### 7. Ship it

- A "Phone app" section in `README.md`: build, install, pairing, releases.
- Tag `phone-v0.1.0` once the owner agrees.

## Working on it

- Tests: `cd tv && mise exec -- ./gradlew :core:jvmTest :youtube:test testDebugUnitTest`. Link parsing and any logic moved into `core` get tests there.
- Develop against a local server (`mise run db`, `mise run server`, `-PantiSocialServer=http://<this machine's LAN IP>:8080`), so testing doesn't spend the owner's real budget. The phone reaches it only while this machine's firewall allows port 8080 from 192.168.2.0/24, and the owner may have removed that rule since. Check the phone against `https://api.yt.nautilus.voltr.org` once, at the end of each slice. The production token is in `~/apps/anti-social-server/server/.env` on nautilus. Never print it.
- Drive the phone over adb yourself: wireless debugging, `adb pair`, then `adb connect`. Batch key events into one `input keyevent` call. Take screenshots with `adb exec-out screencap -p`.
- Commit after each slice. Ask the owner before pushing, tagging a release, or changing the server.
