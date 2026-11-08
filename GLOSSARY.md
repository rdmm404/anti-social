# anti-social

A YouTube client for people who want to watch less. A server keeps everyone's budget, and each client plays YouTube under that budget's rules.

## Watching

**Budget**:
The minutes of video an account may watch in a budget day.
_Avoid_: Quota, allowance

**Budget day**:
The day the budget resets on, starting at a set local hour rather than midnight.

**Session**:
A number of videos, chosen before browsing, that can be watched in one sitting.

**Slot**:
One of a session's videos, taken up once a video has been watched past its sample.

**Sample**:
The first stretch of a video, which can be backed out of without taking up a slot, a few times per session.

**Cooling-off**:
The wait before a looser setting takes effect. Tighter settings apply immediately.

**Check-in**:
A client's report of the video it watched, answered by the server with the remaining budget and an expiry. Playback stops when the expiry passes without another check-in.
_Avoid_: Lease, heartbeat

## Identity

**Account**:
The anonymous owner of a budget, its sessions and its settings. It has no email or password.
_Avoid_: User, profile

**Device**:
An installed client that has joined an account and holds its own token.

**Pairing code**:
A short-lived code, made by a device already in an account, that lets another device join it.
