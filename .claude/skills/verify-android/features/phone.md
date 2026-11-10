# Phone extras

What only the phone app does: it opens YouTube links and shared text, shows a video's description (with links and timestamps) and comments, edits playlists and imports YouTube playlists, and opens the settings page in a Custom Tab.

## Sub-features

- `phone-link` a tapped YouTube link (watch, youtu.be, Shorts, with `t=`) opens the video behind the session gate, starting at `t`.
- `phone-share` text shared to the app opens the YouTube link inside it, or searches for the text.
- `phone-paste` a link pasted into the search box opens it.
- `phone-description` description links are tappable; a timestamp seeks and scrolls to the player.
- `phone-comments` comments load when opened.
- `phone-playlist-add` "+ playlist" adds to a playlist, or creates one and adds.
- `phone-playlist-edit` remove an item; delete a playlist (with confirmation).
- `phone-import` a YouTube playlist link imports it as a playlist of the account.
- `phone-next` at the end of a video opened from a playlist, "Next" plays the following one if the session has videos left.
- `phone-settings` the settings link opens the server's page in a Custom Tab.

## How to get to it (user POV)

- Tap a YouTube link anywhere, or Share → anti-social. Paste a link into search.
- On a video: "+ playlist", Description/Comments "show".
- The playlists tab: create, import, open, delete.

## Driving it with scripts/verify

Preconditions: `$V install phone --signed-in`. For the gate checks, no open session.

- **Link.** `adb shell am start -a android.intent.action.VIEW -d 'https://youtube.com/shorts/<id>' org.voltr.antisocial.phone`. Shows `Waiting for a session` over the video page.
- **Share.** `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Look https://youtu.be/<id>?t=60'" org.voltr.antisocial.phone`. Pick a session; `dumpsys media_session` shows a position just past 60000.
- **Description.** `$V tap-text 'show\|\|'` beside Description, scroll, screenshot, tap a timestamp by coordinates. The position jumps to it.
- **Playlists.** "+ playlist", type a name, `Create and add`. `$V sql "select p.name, i.video_id from playlists p join playlist_items i on i.playlist_id=p.id"`.
- **Import.** VIEW a `https://www.youtube.com/playlist?list=<id>` link: the playlists tab opens with it prefilled. `Import`; the page reads `Imported "<name>" with N videos.` and the list shows it. Count `playlist_items` for the new id.
- **Next.** Play the first item of a playlist, tap near the seek bar's end (about 1030,700 in portrait with controls shown). The end panel offers `Next: <title>` when the session has videos left; with none it offers only Back and Watch again. `$V sql "update sessions set videos=5 ..."` adds room on the throwaway database.

## Gotchas

- Quote URLs with `?` or `&` twice for `adb shell` (`"'...'"`), or the device shell cuts them.
- A YouTube playlist ID that's private or a mix fails the import with a message; use a public creator playlist.
- The settings tab uses Chrome's own session, separate from the app's token: it may ask to sign in.
