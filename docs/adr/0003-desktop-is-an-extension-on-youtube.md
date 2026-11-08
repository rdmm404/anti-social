---
status: accepted
---

# Desktop is an extension that restyles youtube.com

A browser can't fetch YouTube streams for a web app, and the server can't fetch them for it (ADR 0001). So on desktop, anti-social is a browser extension that runs on youtube.com, the way Unhook does. It hides the feed, recommendations, Shorts, comments, end screens and autoplay. It swaps thumbnails for frames YouTube generates and de-shouts titles. It gates browsing behind a session, and it does check-ins and SponsorBlock skips on YouTube's own `<video>` element. YouTube does all the fetching, which is far more robust than extraction. The extension is also the blocking, and it catches YouTube links without any setup. An optional setup script applies browser policies that force-install the extension and turn off incognito and guest windows.

## Consequences

- YouTube changes its page often, so the selectors need regular fixes.
- Desktop looks like YouTube restyled with the shared design tokens, not like the Android screens.
- A browser without the extension gets around it. We accept that.

## Considered options

- **YouTube's official embedded player in our own web app:** YouTube's interface and related videos come back.
- **A local proxy or desktop app running yt-dlp:** yt-dlp and Deno need installing and updating on every machine, and youtube.com still has to be blocked separately. `URLBlocklist` browser policies would do that blocking, but YouTube links would only show a blocked page.
- **A hosts-file block with a local root CA to redirect YouTube links:** workable if the CA is generated per machine, name-constrained to YouTube's domains and its key deleted after use. But it needs a lot of machinery for a redirect the extension gets for free.
