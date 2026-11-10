---
status: proposed
---

# Anonymous accounts, with a secret token per device

The hosted server has no login: nobody gives an email or a password. An **account** is a random ID that is never used as a credential. Each **device** gets its own 256-bit random token when it joins an account and sends it with every request. The server stores only a SHA-256 hash of each token, so a database leak doesn't expose usable tokens, and it can revoke one device without touching the others.

- **Creating an account** costs a small proof-of-work and is rate-limited by IP, so mass sign-ups are expensive.
- **Adding a device** goes the other way round: the new device shows a pairing code, about 8 characters as text and a QR code, and a device already in the account approves it. A TV is good at showing a code and bad at taking one typed on a remote, while the approving device is usually a phone or a browser, signed in and holding a keyboard or a camera. The code is single use and valid for 10 minutes. Only the new device can claim the token, with a secret it got alongside the code, so a code read off the screen is useless to anyone but a signed-in approver. A device that has a browser of its own, such as a phone, skips showing the code and opens the approval page itself.
- **A browser** is a device like any other. Signing in to the settings page gives it a token of its own, and signing out revokes it.
- **Recovery** is an optional high-entropy code shown once. An account whose devices and recovery code are all lost is gone. Its budget history doesn't matter enough to justify anything more.

Impersonation means getting a device token. Tokens can't be guessed and only travel over TLS. A pairing code is short, but it only lets the device that showed it in, and only once a signed-in device approves it; guessing one would at most join someone else's waiting device to your own account. Abuse is handled by Cloudflare in front of the server and rate limits per token. The server stores seconds watched, session state and settings, and hashes video IDs for the once-per-day dedupe, so it never holds a readable watch history.

## Consequences

- Without identity, a user can escape their budget by starting a new account (on Android, by clearing the app's data). The server can't prevent this. A device leaving its account goes through cooling-off, so the deliberate route is slow.

## Considered options

- **The account number as the only secret, as Mullvad does:** one leaked number exposes every device, and one device can't be revoked.
- **Play Integrity or App Attest:** would lock out self-hosters and anyone building the app themselves.
