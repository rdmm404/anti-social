# Original titles in lists (the Zulu strategy)

Researched and half built on 2026-11-10. Status: titles work; dates of videos newer than a month don't yet.

## The problem

YouTube swaps a video's title for a translation (the uploader's, or its own) into the language a list is
asked for in. The owner watches Spanish-speaking channels whose titles came back in English. YouTube's own
app does the same, and there is no "original title" switch.

- Only lists are affected: search, a channel's videos, playlists. The video page already shows the
  original (NewPipeExtractor reads `videoDetails.title`, which YouTube doesn't translate).
- NewPipeExtractor v0.27.0 ignores the `Localization` given to `NewPipe.init` for YouTube: its supported
  list holds only `en-GB`, so every request goes out as `hl=en-GB`. `forceLocalization()` on an extractor
  gets past that.

## What was measured

Against Charly Sinewan's channel (`UCqZQXbQTF6VI_i5QWFaVxSQ`) and a MrBeast video, with plain HTTP requests:

| Route | Title |
| --- | --- |
| List requests (`browse`, `search`, `next`) with `hl=en-GB` or `en` | translated |
| The same with `hl=zu`, or `hl=es` for the Spanish channel | original |
| `player` `videoDetails.title`, any `hl` | original |
| oEmbed (`youtube.com/oembed?url=…&format=json`), any language | original |
| Watch page `<title>` and `og:title` | original |

No client type (mobile web, TV) changed the list answer. noembed returned a stale title. The channel RSS
feed is said to hold originals, but it mostly answered 404/500.

## The two ways out

1. **Ask lists in Zulu** (chosen). Next to nobody translates into it, so the original comes back.
   PipePipe hardcodes the same. No extra requests. The cost is that dates and counts arrive in Zulu.
2. **Look each title up through oEmbed.** One small request per video (8 in parallel took 0.57 s), with
   dates and counts left alone. Needs a cache, a cap on parallel requests and a fallback. Not built.
   It's also the only way to tell whether a title is the original: compare the two.

A title can still come back in Zulu, if its uploader translated into Zulu or YouTube does it itself. The
list alone can't tell. The video page would show the original, and oEmbed could check. Not handled.

## What's built (tv/youtube, `NewPipeYouTube.kt`)

- Search, channel and playlist extractors are built by hand, given `forceLocalization(Localization("zu"))`,
  then `fetchPage()`, then the `getInfo(extractor)` overload. Without `fetchPage()` it fails or comes back
  empty. A channel's first answer already holds its videos tab, so the channel extractor is the one forced.
- Later playlist pages come from the same extractor (`extractor.getPage`), not `PlaylistInfo.getMoreItems`,
  which would build an English one.
- The date shown is written from the parsed `uploadDate` (`agoText`: "3 weeks ago"), not YouTube's words.
- Checked on the live test and on the owner's phone: Spanish titles, view counts and durations are right.

## What's left

- **Dates newer than a month are missing.** YouTube writes "ezinsukwini ezingu-2 ezedlule." (2 days ago),
  and NewPipe's Zulu patterns (`izinsuku`, `usuku`, `amaviki`, …) don't match that form, so `uploadDate` is
  null. Months and years parse ("1 inyanga edlule", "8 iminyaka edlule"). Hours, days and weeks need their
  real Zulu forms collected and parsed here. `LiveTest > lists keep the titles their uploaders wrote`
  (`./gradlew :youtube:test -Plive`) fails on this until it's done.
- **Words NewPipe only knows in English**: "no views", "Streamed … ago", premieres. A video with a null
  view count was seen at the top of the channel list; not looked into.
- The channel's name is asked for in Zulu too. Not checked whether a name is ever translated.

## Sources

- NewPipeExtractor `YoutubeService.java` (supported languages) and `YoutubeStreamExtractor.java` (`getName`), v0.27.0
- https://github.com/TeamNewPipe/NewPipe/issues/3089
- PipePipeExtractor `YoutubeService.java` ("to prevent YouTube from auto-translating video titles")
- https://github.com/libre-tube/LibreTube/issues/7832
- https://github.com/FreeTubeApp/FreeTube/pull/10029
