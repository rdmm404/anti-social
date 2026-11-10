# Session gate

With no session open, every tab except History shows "How many videos?" and a 1-3 picker instead of its content. Picking a number opens a session for that many videos, and the tabs open up. Once the session's videos are used, or between sessions, the gate says when the next one opens.

## Sub-features

- `gate-shown` signed in with no session, search, channels and playlists all show the picker.
- `gate-history-open` history stays readable with no session.
- `gate-open` choosing a number opens a session, and the top bar changes from `2h left` to `2 videos · 2h`.
- `gate-session-row` the server records the session and its video count.

## How to get to it (user POV)

- TV: open the app while signed in. The picker has focus.
- Phone: the same screen, by touch. _Not built yet._

## Driving it with scripts/verify

Preconditions:

- `$V install tv --signed-in`. Doctor is ready.

- **Gate shown.** Run `$V wait-text 'How many videos\?' 30`, then `$V shot gate`. `$V ui` lists `1`, `2`, `3` and `2h left today` (the default 120 minutes).
- **History open.** From the fresh launch, `$V keys DPAD_UP DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER`. `$V wait-text 'Nothing watched yet\.'` and `$V shot gate-history`. `How many videos?` isn't in `$V ui`.
- **Open a session.** Move focus to a gated tab, then down into the picker. Step with `$V keys <key>` and `$V focused` until it prints `2`, then `$V keys DPAD_CENTER`. `$V wait-text '2 videos'` matches the top bar. Take `$V shot session-open`.
- **Session row.** Run `$V evidence session "select day, videos, started_at from sessions"`. One row with `videos = 2`.

## Gotchas

- Moving along the tab bar switches tabs without `DPAD_CENTER`, and a long key sequence can land somewhere unexpected. Step with `$V focused` rather than sending a sequence you haven't checked.
- A session gap (default 20 minutes) follows each session, so a second session in the same run is refused. Start a fresh run to retry.
