# Channels

The Channels tab lists the channels you subscribe to. A channel's page shows its newest videos and a Subscribe or Unsubscribe action. Subscriptions are stored on the server, so every device sees the same list.

## Sub-features

- `channels-list` the tab lists subscriptions by name, or says there are none.
- `channels-subscribe` subscribing from a channel page adds it to the list and the server.
- `channels-unsubscribe` unsubscribing removes it from both.
- `channels-page` a channel shows its newest videos.

## How to get to it (user POV)

- TV: the channels tab, or the channel name under a playing video.
- Phone: the same tabs by touch. _Not built yet._

## Driving it with scripts/verify

Preconditions:

- `$V install tv --signed-in`, a session open (the tab is gated), and the emulator online.

- **Empty list.** Open the tab: `$V keys DPAD_UP DPAD_RIGHT DPAD_CENTER`. `$V wait-text 'No channels yet'` matches `No channels yet. Open a channel and subscribe.` Take `$V shot channels-empty`.
- **Subscribe.** Play a search result (see [Playing a video](./playing.md)), choose its channel name to open the channel page, then choose `subscribe` (`$V wait-text 'subscribe'`). Take `$V shot channel-subscribed`. Run `$V evidence subscriptions "select channel_id, name from subscriptions"` for one row.
- **Listed.** Back on the channels tab, `$V wait-text '<channel name>'`.
- **Unsubscribe.** On the channel page, choose `unsubscribe`. The subscriptions query returns no rows.

## Gotchas

- The tab is gated like the others, so open a session first.
- A channel page's video list comes from YouTube, so it needs the emulator's internet.
