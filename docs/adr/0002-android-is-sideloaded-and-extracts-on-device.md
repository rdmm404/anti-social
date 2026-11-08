---
status: accepted
---

# Android is sideloaded and gets video on the device

The Android phone and TV apps get streams from YouTube on the device with NewPipeExtractor, play them with Media3/ExoPlayer, and skip SponsorBlock segments. The Play Store doesn't allow apps that fetch YouTube streams directly, so the apps are sideloaded only. We accept that: people who want this app will sideload, and the alternative, YouTube's official embedded player, brings back YouTube's interface and related videos.

YouTube is blocked on the device without a VPN, because Android allows one VPN at a time and Tailscale has to keep working:

- the YouTube app is disabled once during setup with `adb shell pm disable-user com.google.android.youtube`;
- the app handles youtube.com and youtu.be links, which the user enables under App info → Open by default (Android 12 and later don't let a sideloaded app claim them automatically);
- an accessibility service sends youtube.com in a browser, and a re-enabled YouTube app, to anti-social.

None of this depends on the network, so turning off Tailscale or switching to mobile data doesn't get around it.

## Consequences

- Phone and TV share everything but their screens. The shared core (API client, check-ins, session and budget state, SponsorBlock, screen state) is Kotlin Multiplatform so an iOS app can reuse it. Extraction and playback are Android-only and sit behind interfaces, because NewPipeExtractor needs a JVM.
- SponsorBlock data is CC BY-NC-SA 4.0: the app credits it, and the hosted service can't be sold without the SponsorBlock maintainers' agreement.

## Considered options

- **A local VPN that filters DNS:** conflicts with Tailscale.
- **React Native:** extraction, blocking and the existing TV app are all native Android anyway.
