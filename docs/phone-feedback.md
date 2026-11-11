# Phone app: owner feedback, 2026-11-10

From the first session on the owner's OnePlus 12 (debug build against a test server).

## Proven on the real phone

- Pairing through the approval sheet in Chrome; the app returns to the front signed in.
- Search and playback.
- 3 minutes with the screen locked and the phone dozing: playback continued, 11 check-ins (one per 15 s).
- Picture-in-picture on Home: plays, check-ins continue; closing the window pauses.

## Tested with the owner on 0.1.0

- A refused check-in: daily minutes capped below today's use on production (one `settings` row, removed
  after). The next check-in was refused, playback paused, the app showed "No time left today", and
  check-ins kept asking every 15 s while held. The player's one-line message said "Waiting for a session"
  for an out-of-time refusal; fixed after 0.1.0.
- Tapping a YouTube link in another app works, once App info → Open by default → links are added by hand
  (Android 12+ doesn't hand youtube.com to an unverified app).
- Sharing from the YouTube app to anti-social works.
- The owner's YouTube blocker app must allow anti-social (youtube.com, youtubei.googleapis.com,
  *.googlevideo.com, i.ytimg.com), ideally per app.

## Changes asked for

1. **Bottom navigation.** search / channels / playlists / history move to a bar at the bottom.
2. **The video page shows the bottom bar too**, so there's always a way out of it.
3. **Mini-player.** Leaving a video keeps it playing in a bar above the bottom navigation, like YouTube:
   tap it to return to the video, ✕ to stop it.
4. **Top bar.** The "anti-social" title is in the way: the app icon instead. Time left ("2h left") centred.
   Settings stays top right (the owner considered moving it to the menu, and settled on keeping it).
5. **Overlay appears with a delay after tapping the video.** Cause: the tap waits ~300 ms to rule out a
   double tap (±10 s seek). Show it on the first tap; a quick second tap still seeks.
6. **Tapping a paused video doesn't hide the overlay** (it flickers and stays). Cause: a paused player
   forces the controls back on.
7. **"Exit full screen" does nothing after rotating into landscape.** Cause: it only releases a landscape
   lock. Force portrait until the phone is upright, then follow the sensor again (like YouTube).
8. **Speed and quality move into the video overlay**, and tapping one lists the options to pick from
   instead of cycling through them.
9. **The session counter moves into the overlay** as a small, unobtrusive line, instead of sitting between
   the buttons and the description.
10. **Skeleton loading.** Opening a video shows what's already known (thumbnail, title, channel, views)
    instead of a blank "Loading video…".
11. **Share button** on the video page, sharing a YouTube link (https://youtu.be/<id>).

## Done (2026-11-10, verified on the emulator)

All eleven changes above are in, checked on the phone emulator against a throwaway server, and a minified
release build was checked the same way (pairing, search, playback, comments). Exit full screen was tested
the way it failed: phone turned sideways, exit, held portrait while sideways, back to following the phone
once upright.

## Release 0.1.0 on the owner's phone (2026-11-10)

- phone-v0.1.0 (the official, release-signed APK, pointing at api.yt.nautilus.voltr.org) is installed on
  the owner's phone and was used all day without problems.
- OxygenOS refused to sideload the APK from the browser, so it went on with `adb install` over wireless
  debugging. Later releases may need the same, or "Install unknown apps" allowed for the browser.
- A release build can't update a debug build (different keys): uninstall the debug build first.
- To pair a browser with the production server, the device token is `ANTI_SOCIAL_TOKEN` in
  `~/apps/anti-social-server/server/.env` on nautilus (the running server's compose folder).

## Open question

- The server's approval page says "Check the code matches the one on the device's screen", but the phone
  shows no code. Reword it? (Server change: ask first.)

## Second round, from a day on 0.1.0 (2026-11-10)

Done, and checked on the phone emulator:

1. **Seeking shows what it did.** A double tap, or the ±10s buttons, shows "+10s" on the side it went;
   further taps there keep seeking and add up ("+20s").
2. **The middle of the video doesn't seek.** Only the outer 35% on each side does.
3. **The phone's navigation bar sits on the bottom bar's colour**, not the page's.
4. **Swipe down on the video leaves it** (it carries on in the mini-player). In full screen, a swipe down
   leaves full screen.
5. **The video page's actions are one row** (subscribe, + playlist, share), with the channel above them on
   the title's left edge.
6. **Speed and quality options wrap** onto more lines instead of running off the side.
7. **Lists show the titles their uploaders wrote**, not YouTube's translations: lists are asked for in
   Zulu (TV too). Half done: dates of videos newer than a month are missing. See `docs/original-titles.md`.

Also checked on the owner's phone, as "anti-social dev" beside the released app, against a throwaway
server tunnelled over adb: 1, 2, 3, 4 (portrait) and 5. Not yet on the phone: 6, and 4 in full screen.

Open:

- **The settings page asked to sign in every time.** Likely cause: the cookie was `SameSite=Strict`, and
  Chrome doesn't send those on a page another app opens. Now `Lax`; needs a deploy, and checking after it.
- **Settings in the app.** Decided: no app settings for now; the server's page stays the one place.
