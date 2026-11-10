# Playing a video

With a session open, you search, pick a result and it plays. Before it starts and about every 15 seconds while it plays, the app checks in. The server charges what was watched, and stops the video when the session or the day runs out.

## Sub-features

- `play-search` a query returns video results with titles and channels.
- `play-start` choosing a result plays it after a successful check-in.
- `play-checkins` check-ins keep arriving while it plays, and the seconds are charged.
- `play-refused` once the day's time is out, the server refuses and playback stops with the reason.
- `play-background` phone only: playback and check-ins continue with the screen locked and in picture-in-picture; a refused check-in pauses it there too; closing the PiP window pauses.

## How to get to it (user POV)

- TV: the search tab, type a query, choose a result.
- Phone: the same, by touch, or from a YouTube link (see [Phone extras](./phone.md)).

## Driving it with scripts/verify

Preconditions:

- `$V install tv --signed-in`, a session opened as in [Session gate](./session-gate.md), and the emulator online.

- **Search.** Focus the search box and type: `$V keys DPAD_UP DPAD_DOWN DPAD_CENTER`, `$V type 'veritasium'`, `$V keys ENTER`. Then `$V wait-text 'Veritasium' 30` and `$V shot search-results`.
- **Play.** Choose the first result with `$V keys DPAD_DOWN DPAD_CENTER`. The log shows `POST path=/api/checkins status=200` within a few seconds. Take `$V shot playing`.
- **Charged.** After 30 seconds or more, run `$V evidence watched "select video_id, title, charged_seconds from watched"`. One row whose `charged_seconds` grows between two runs.
- **Refused.** Set Daily minutes to 1 on the settings page (lowering is immediate; raising waits for cooling-off), keep playing, and wait for the stop. The player shows the out-of-time message, and the log shows check-ins answered with `allowed: false`.

- **Phone background.** While playing: `adb shell input keyevent KEYCODE_SLEEP` (screen off) or `KEYCODE_HOME` (PiP; `dumpsys activity activities | grep mode=pinned`). Check-ins keep arriving in the log, and `dumpsys media_session | grep -m1 state=PlaybackState` says `PLAYING`. Close PiP by dragging it to the dismiss target: `adb shell input draganddrop <cx> <cy> 540 2330 2000` (read the window from `mBounds` in that dumpsys); the state becomes `PAUSED`.
- **Phone refusal.** The quickest refusal on the throwaway database is `$V sql "delete from sessions"` while playing. The next check-in is answered `200` with a refusal, playback pauses, and the gate shows over the video.
- **Phone rotation.** With auto-rotate on, `adb -s emulator-5582 emu rotate` turns the emulator's sensor a quarter turn each time (four calls get back upright); the app follows into full screen. "full screen" / "exit full screen" in the overlay hold the screen that way until the sensor agrees, then follow it again: check with `dumpsys window displays | grep -m1 mCurrentRotation` and `dumpsys activity activities | grep -m1 requestedOrientation`. Android shows its own "Viewing full screen" notice once: `$V tap-text 'Got it\|\|'`.
- **Phone overlay.** One tap on the video shows the controls at once; they hide after 3 s while playing. The top row has the session line, speed and quality: each opens a row of choices (`dumpsys media_session` shows `speed=`). The overlay isn't in `$V ui`, so tap by coordinates (portrait, 1080x2400: speed about 889,187, quality 1000,187, full screen 906,610) and tap only while it shows, or the tap just brings it back.
- **Phone mini-player.** Back from a playing video, or any bottom-bar section: the video keeps playing in a bar above the bottom bar (title, pause, ✕ at about 1000,2119). Tapping it reopens the page; ✕ closes it (`dumpsys media_session` has 0 sessions).

## Gotchas

- Phone: a drag that starts at the screen's edge is Android's Back gesture. Tap the seek bar instead of dragging from its start.
- `$V ui` prints text as uiautomator XML, so `&` shows as `&amp;` and newlines as `&#10;`. Check a screenshot before calling it a bug.

- Playback works on the x86 phone emulator (proven 2026-11-10).
- Search filters out Shorts and live streams. Pick a query that returns regular videos.
- Check-ins only charge stretches that actually played. A paused player isn't charged.
