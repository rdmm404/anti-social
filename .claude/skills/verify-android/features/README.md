# anti-social Android verification map

The maintained source for verifying what a user does in the Fire TV and phone apps. Read this index, then use the matching feature file as the recipe. The phone app is being built slice by slice (`docs/plan-phone.md`). A phone entry point marked _not built yet_ is reported as unreachable, never as verified through the TV.

## Baseline preconditions

- A run started with `$V up`, a device chosen with `$V device ...`, and `$V doctor` ending in `doctor: ready`.
- The run's database starts empty apart from the `configured` owner device. Nothing is seeded, so each recipe creates what it needs through the app.
- The emulator needs internet for YouTube (search, channels, playback). The server needs none.

## Driving conventions

- TV: drive with `$V keys` (D-pad, `DPAD_CENTER` to select, `BACK`). Phone: drive with `$V tap-text`.
- Wait on text with `$V wait-text`, never a fixed sleep.
- On the TV, check where focus is with `$V focused` before pressing `DPAD_CENTER`. Moving along the tab bar switches tabs by itself.
- Every recipe starts from a fresh run unless it says otherwise. Recipes that need a signed-in app use `$V install <app> --signed-in`.

## Proof and skip reporting

- A screenshot of the action and of the result (`$V shot`), and the server-side effect (`$V evidence` or `$V log`).
- Name the feature ID, the entry point, the device and the app with every artifact.
- Report an unreachable path with the command tried and the precondition that wasn't met.

## Feature entry contract

Each file has an H1, one paragraph on the user-visible behaviour, then four H2s in order: `Sub-features`, `How to get to it (user POV)`, `Driving it with scripts/verify`, `Gotchas`.

## Features

Pairing and the session gate were proven on the emulator (TV app) on 2026-11-10. Every phone recipe was proven on the `phone-sim` emulator the same day. The TV recipes for playing, channels and history are drafted from the code and haven't been run on the TV.

- [Pairing](./pairing.md): a new device signs in by having its code approved on the settings page.
- [Session gate](./session-gate.md): no session means no browsing; the 1-3 picker opens one.
- [Playing a video](./playing.md): search, play, and check-ins charged to the budget.
- [Channels](./channels.md): subscribe, the channel list, unsubscribe.
- [History](./history.md): today's watched videos, readable without a session.
- [Phone extras](./phone.md): YouTube links and shares, the description and comments, playlists and import, the settings tab.
