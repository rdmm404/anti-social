---
status: accepted
---

# Clients play YouTube; a hosted server keeps the budget

anti-social is meant to be used by people who can't run a server, so one hosted server (open source, self-hostable) has to serve everyone. It can't keep fetching and streaming video the way the self-hosted version does: YouTube quickly flags datacenter IPs as bots, and every user's video would pass through, and be paid for by, that server. So each client gets video from YouTube itself, from the user's own IP, and the server only decides how much watching is allowed: budget, sessions and settings with cooling-off.

The server can no longer refuse to send video, so it enforces through **check-ins**. While a video plays, the client reports the stretches of video watched since its last check-in, about every 15 seconds. The server charges them, with each second of a video still charged once per budget day, and replies with the remaining budget and an expiry. The client stops at zero budget, and stops when the expiry passes without a successful check-in. The server sets the expiry, around 60 seconds, so going offline or two devices playing at once can overshoot by about a minute at most.

## Consequences

- Time is charged for what the client reports watching, not for what the server delivers. It's more accurate than today's delivered-media charging, which pays for 20-30 seconds of buffer ahead of the playhead, but it trusts the client. That's acceptable: the person being held back is the user in a weak moment, not an attacker, so bypassing should take deliberate effort, not be impossible.
- The server only handles small JSON requests and is rewritten in Go. The Python server stays until no client needs it, and its ledger rules and tests are the spec for the rewrite.
- Settings live on the server, so cooling-off can't be undone on a device.

## Considered options

- **Hosting today's server for everyone:** blocked by YouTube, and costly in bandwidth.
- **Reporting usage after the fact with no expiry:** a client that goes offline could play indefinitely.
- **Granting the whole remaining budget as one long expiry:** lets two devices spend the same time, and stops the server from applying a tighter limit or a session's end until the next check-in.
