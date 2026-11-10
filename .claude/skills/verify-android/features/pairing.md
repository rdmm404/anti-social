# Pairing

An unpaired app can't do anything until a signed-in device approves it. The TV shows a code and a QR code. You approve it on the settings page and the TV signs itself in a few seconds later. The phone shows no code: it opens the approval page itself, as a partial Custom Tab (a tall sheet over the app), and returns to the front signed in.

## Sub-features

- `pair-code` the unpaired TV shows a code like `ABCD-EFGH`, the `/pair` link and "Waiting for approval…".
- `pair-approve` approving on the settings page reports "<device> is paired".
- `pair-claim` the TV leaves the pairing screen for the session picker without any input.
- `pair-device-row` the server lists the new device under its model name.
- `pair-phone-tab` the phone opens `/pair?code=…` in a Custom Tab and returns signed in.

## How to get to it (user POV)

- TV: install, or clear the app's data, and open it.
- Settings page: scan the QR code, or open `/pair` and type the code, then sign in with the token if asked, and choose Approve.
- Phone: open the app for the first time and choose Pair.

## Driving it with scripts/verify

Preconditions:

- `$V install tv` (unpaired). Doctor is ready.

- **Code shown.** Run `$V wait-text '[A-Z0-9]{4}-[A-Z0-9]{4}' 60`, then `$V shot pairing-code`. It prints the code. The server log shows `POST path=/api/pairings status=201`.
- **Approve.** With `export AGENT_BROWSER_SESSION=verify-android` and `ab() { mise exec node@26.7.0 -- agent-browser "$@"; }`, run `ab open "$($V url)/pair?code=<code>"`, then `ab snapshot -i`. A signed-out browser shows heading `Sign in` with textbox `Token`. Fill it with `$V token` and click `Sign in`. The page becomes `Pair a device` with the code prefilled. Click `Approve`. The body reads `<device> is paired. It signs in within a few seconds.` Save `ab screenshot .verify/current/shots/pair-approved.png`.
- **Claim.** Run `$V wait-text 'How many videos\?' 30`, then `$V shot paired-home`. The log shows `POST path=/api/pairings/claim status=200` followed by `GET /api/status 200`.
- **Device row.** Run `$V evidence pairing "select name, created_at from devices order by created_at"`. It lists `configured`, `Browser` (the approving browser) and the device's model name (`AOSP TV on x86` on the emulator).

- **Phone.** `$V install phone` (unpaired), `$V tap-text 'Pair\|\|'`. The sheet opens Chrome on `/pair?code=…`. On a fresh emulator, Chrome's first run asks first: tap `Use without an account`. Signed out, the sheet shows `Sign in`: tap the Token field (about 540,1088 on 1080x2400), `adb shell input text "$($V token)"`, tap `Sign in` (about 220,1223), then `$V tap-text 'Approve\|\|'`. Within a few seconds the app is in front at the session picker. The device row is `sdk_gphone64_x86_64`.

## Gotchas

- The phone must not open the approval page as a full-screen tab. Behind one, Android 14+ freezes the app (logcat `freezing org.voltr.antisocial.phone`), so it stops polling, and Android 15 blocks it from bringing itself back. The partial tab keeps it visible.
- `$V ui` on the sign-in page prints the run's token. It's the throwaway run token; never paste production output.

- A code is valid for 10 minutes. Approve within that window, or the TV fetches a new code and the old one fails.
- The browser that signs in becomes a `Browser` device row of its own. That's expected (ADR 0004).
- On the emulator, a code that appears before Wi-Fi is up gets replaced by "Can't reach the anti-social server." until the app retries. `$V device emulator` waits for the network to prevent this.
- On the real Fire TV, a debug install replaces the production build and unpairs it. Ask the owner first.
