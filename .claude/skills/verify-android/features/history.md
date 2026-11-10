# History

The History tab lists what was watched in the current budget day. It stays readable when no session is open, so you can see what you've used.

## Sub-features

- `history-empty` a new day shows no videos.
- `history-listed` a video that played shows up with its title and channel.
- `history-ungated` the tab opens without a session.

## How to get to it (user POV)

- TV: the history tab, the fourth tab.
- Phone: the same, by touch. _Not built yet._

## Driving it with scripts/verify

Preconditions:

- `$V install tv --signed-in`.

- **Ungated and empty.** With no session open, `$V keys DPAD_UP DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER`, then `$V wait-text 'Nothing watched yet\.'` and `$V shot history-empty`. No `How many videos?` in `$V ui`.
- **Listed.** After a video has played as in [Playing a video](./playing.md), reopen the tab and `$V wait-text '<video title prefix>'`. Take `$V shot history-listed`. Run `$V evidence history "select title, channel, charged_seconds from watched"` and check it matches the screen.

## Gotchas

- History is per budget day, which starts at 4:00 local time (`ANTI_SOCIAL_DAY_START`). A run spanning 4:00 sees an empty list afterwards.
- Titles are de-shouted on screen, so match a case-sensitive prefix that survives that, or use a regex.
