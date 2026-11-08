---
status: proposed
---

# Anonymous accounts, with a secret token per device

The hosted server has no login: nobody gives an email or a password. An **account** is a random ID that is never used as a credential. Each **device** gets its own 256-bit random token when it joins an account and sends it with every request. The server stores only a SHA-256 hash of each token, so a database leak doesn't expose usable tokens, and it can revoke one device without touching the others.

- **Creating an account** costs a small proof-of-work and is rate-limited by IP, so mass sign-ups are expensive.
- **Adding a device** uses a pairing code made by a device already in the account. The code is about 8 characters, shown as text and a QR code, single use, valid for 10 minutes, and invalidated after 5 wrong tries. The new device swaps it for its own token.
- **Recovery** is an optional high-entropy code shown once. An account whose devices and recovery code are all lost is gone. Its budget history doesn't matter enough to justify anything more.

Impersonation means getting a device token or a live pairing code. Tokens can't be guessed and only travel over TLS. Pairing codes are short, but they expire quickly and allow only a few tries. Abuse is handled by Cloudflare in front of the server and rate limits per token. The server stores seconds watched, session state and settings, and hashes video IDs for the once-per-day dedupe, so it never holds a readable watch history.

## Consequences

- Without identity, a user can escape their budget by starting a new account (on Android, by clearing the app's data). The server can't prevent this. A device leaving its account goes through cooling-off, so the deliberate route is slow.

## Considered options

- **The account number as the only secret, as Mullvad does:** one leaked number exposes every device, and one device can't be revoked.
- **Play Integrity or App Attest:** would lock out self-hosters and anyone building the app themselves.
