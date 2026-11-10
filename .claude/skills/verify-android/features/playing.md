# Playing a video

With a session open, you search, pick a result and it plays. Before it starts and about every 15 seconds while it plays, the app checks in. The server charges what was watched, and stops the video when the session or the day runs out.

## Sub-features

- `play-search` a query returns video results with titles and channels.
- `play-start` choosing a result plays it after a successful check-in.
- `play-checkins` check-ins keep arriving while it plays, and the seconds are charged.
- `play-refused` once the day's time is out, the server refuses and playback stops with the reason.
- `play-background` phone only: playback and check-ins continue with the screen locked and in picture-in-picture. _Not built yet._

## How to get to it (user POV)

- TV: the search tab, type a query, choose a result.
- Phone: the same, by touch, and later from a shared YouTube link. _Not built yet._

## Driving it with scripts/verify

Preconditions:

- `$V install tv --signed-in`, a session opened as in [Session gate](./session-gate.md), and the emulator online.

- **Search.** Focus the search box and type: `$V keys DPAD_UP DPAD_DOWN DPAD_CENTER`, `$V type 'veritasium'`, `$V keys ENTER`. Then `$V wait-text 'Veritasium' 30` and `$V shot search-results`.
- **Play.** Choose the first result with `$V keys DPAD_DOWN DPAD_CENTER`. The log shows `POST path=/api/checkins status=200` within a few seconds. Take `$V shot playing`.
- **Charged.** After 30 seconds or more, run `$V evidence watched "select video_id, title, charged_seconds from watched"`. One row whose `charged_seconds` grows between two runs.
- **Refused.** Set Daily minutes to 1 on the settings page (lowering is immediate; raising waits for cooling-off), keep playing, and wait for the stop. The player shows the out-of-time message, and the log shows check-ins answered with `allowed: false`.

## Gotchas

- Playback on the x86 emulator hasn't been proven yet. If YouTube extraction or software decoding fails there, report it and use a real device with the owner's go-ahead.
- Search filters out Shorts and live streams. Pick a query that returns regular videos.
- Check-ins only charge stretches that actually played. A paused player isn't charged.
