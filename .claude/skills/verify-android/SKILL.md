---
name: verify-android
description: Drive the anti-social Android apps (Fire TV in tv/tv, phone in tv/phone) on an emulator or a real device against a disposable Go server, and prove a feature with screenshots plus server-side rows. Use to verify a phone-plan slice, confirm a TV or phone change works on a device, or reproduce a bug in the app.
---

# Verify the Android apps

Proof means the real user path on a device: what's on screen, plus what the server recorded. The phone and the TV share their screens and the server, so one harness drives both. `scripts/verify` does every step; this file says which step to run and what counts as proof. The recipe for each feature is in `features/`. Read `features/README.md` before driving.

All commands run from the repo root as `V=.claude/skills/verify-android/scripts/verify`, then `$V <command>`.

## Launch

1. `$V up` builds the server into a new run directory `.verify/<run-id>/`, creates a fresh database `anti_social_verify_<run-id>` in the local Postgres (`mise run db`, which it starts if needed), generates an owner token, and starts the server on `:38080` (`VERIFY_PORT` overrides this). Migrations apply on start. It's ready when it prints `run <id>: server on :38080`.
2. Choose a device:
   - `$V device emulator` starts the `firetv-sim` AVD read-only and headless on `emulator-5580`. It's ready once it prints `device emulator-5580`, which waits for Wi-Fi to reach the server. Booting takes about a minute.
   - `$V device tv 192.168.2.56:5555` or `$V device phone <ip:port>` for a real device. See Real devices below first.
3. `$V install tv` (or `phone`) builds a debug APK pointed at this run's server, reinstalls it, and launches it. The app starts unpaired. `$V install tv --signed-in` bakes the run's owner token in instead, for features that aren't about pairing.
4. `$V doctor` must end with `doctor: ready` before you drive.

## Doctor

`$V doctor` is read-only. It checks that the server is our process, `/healthz` answers, port 38080 belongs to our server, the run's database answers, the device is online, and the app is installed. Run it first whenever anything looks off. A `NOT READY` line names what to fix. Drive only an instance that this run's `up` started.

## Drive

- `$V ui` lists every on-screen node with text or a description, as `text|description|x y` (the node's centre). Compose screens expose their text here, so text is the stable handle.
- `$V wait-text '<regex>' [seconds]` waits for a node whose text matches (case-sensitive, ERE) and prints it. Wait for the next screen's text rather than sleeping.
- `$V tap-text '<regex>'` waits for the text, then taps its centre. This is the phone's user path.
- `$V focused` prints the focused node's text, which is where the TV's cursor is.
- `$V keys DPAD_DOWN DPAD_CENTER ...` sends key events in one call. This is the TV's user path, since the TV has a remote and no touch. On the real Fire TV each `input keyevent` call costs about 5 s, so batch keys.
- `$V type 'some text'` types into the focused field.
- `$V adb <args>` runs adb against the run's device.
- The settings page, for pairing approval and limits, is the run's server at `$($V url)`, driven with agent-browser. Under the current Node it needs `mise exec node@26.7.0 -- agent-browser` and its own session (`export AGENT_BROWSER_SESSION=verify-android`). Sign in with `$V token`. That is the run's throwaway owner token, never the production one.

## Evidence

Everything lands in `.verify/<run-id>/` (gitignored; `.verify/current` points at the latest run):

- `$V shot <name>` saves `shots/<name>.png` and the matching `shots/<name>.ui.txt` dump.
- `$V evidence <name> "<sql>"` runs a query against the run's database and saves it as `evidence/<name>.txt`.
- `server.log` has one line per request: method, path, status.

A proof has all three: a screenshot of the action, a screenshot of the resulting screen, and the server-side side effect (a row, or a request line). Drive the app as a user would. Approve pairing through the settings page, not with curl, and start sessions with the on-screen picker, not the API. Record which entry point you used and on which device. A feature proven on the emulator says nothing about the real phone's background playback, picture-in-picture or rotation, so report those as emulator-only until a real device covers them.

## Cleanup

`$V down` stops only the server and emulator this run started (checked by PID and command line), drops the run's database, and deletes the server binary and the token. Screenshots, dumps, evidence and logs stay in `.verify/<run-id>/`. Run it after every run, failed ones included. Close the browser session with `mise exec node@26.7.0 -- agent-browser close`.

## Real devices

- The Fire TV (`192.168.2.56:5555`) and the owner's phone (a OnePlus 12 on Android 16, over wireless debugging: `adb pair`, then `adb connect`) are the owner's real devices, paired with production. A debug install replaces the release build, because the signing keys differ. That signs the device out of production until it's re-paired. Ask the owner before installing on either.
- A real device reaches this machine's server at its LAN IP, which `$V device` works out. The firewall has to allow port 38080 from 192.168.2.0/24 (`sudo ufw allow from 192.168.2.0/24 to any port 38080 proto tcp`). Ask the owner to add the rule, and to delete it afterwards.
- To check against production instead (`https://api.yt.nautilus.voltr.org`), use the device as the owner has it. That spends the owner's real budget, so ask first.
