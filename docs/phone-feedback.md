# Phone app: owner feedback, 2026-11-10

From the first session on the owner's OnePlus 12 (debug build against a test server).

## Proven on the real phone

- Pairing through the approval sheet in Chrome; the app returns to the front signed in.
- Search and playback.
- 3 minutes with the screen locked and the phone dozing: playback continued, 11 check-ins (one per 15 s).
- Picture-in-picture on Home: plays, check-ins continue; closing the window pauses.

## Still to test with the owner

- A refused check-in on the phone (screen locked, and in picture-in-picture).
- Tapping a YouTube link in another app. Android 12+ doesn't hand youtube.com links to an unverified app by
  default, so the owner may need App info → Open by default → add links. See how OxygenOS shows this.
- Sharing from the YouTube app to anti-social.
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

## Open question

- The server's approval page says "Check the code matches the one on the device's screen", but the phone
  shows no code. Reword it? (Server change: ask first.)
