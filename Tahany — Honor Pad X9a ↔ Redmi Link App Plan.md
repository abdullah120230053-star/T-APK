# Tahany — Honor Pad X9a ↔ Redmi Link App Plan

Nov 20, 2026 · @DAMA

## Goal and scope

One app, installed on both devices, links the Honor Pad X9a and android phone over local Wi-Fi and Bluetooth and does six jobs: file transfer, clipboard sync, notification mirroring, the phone screen on the tablet, call forwarding to the tablet, and automatic screenshot sync in both directions, and to keep saved screenshots in specific place as album include all.\


It is a standalone app, not part of Honor Connect. Honor Connect needs a HONOR ID on Honor devices, and its deepest features (dragging windows, call transfer) are system services an installed APK cannot reach. Honor AI Space is a smart-home app and does not link a phone to a tablet.

**Devices**

- Tablet: Honor Pad X9a, MagicOS 9.0 (Android 15), Snapdragon 685, Bluetooth 5.1, dual-band Wi-Fi, no NFC, no SIM on the Wi-Fi model.
- Phone: Redmi on MIUI or HyperOS. The Android version is not known yet (open question for you).

**Out of scope for now:** dragging app windows between devices, HONOR ID sign-in features, and routing the Redmi's call audio into the tablet (see the call forwarding limits below).

## What REA is and how Tahany uses it

REA (Reverse Engineer Anything) is a free, MIT-licensed toolkit that lets a coding agent such as Claude Code analyse an app or binary and explain how a feature works, backed by evidence from the files you give it. It explains behaviour; it does not hand you source code you can ship. Two GitHub projects share the name, so confirm which one you install: [morluto/rea](https://github.com/morluto/rea) is an MCP server with about 170 analysis tools (Android manifest, classes and decompiled methods, JavaScript, native code through Ghidra), and [REA\_Skill](https://github.com/smartworldarafath/REA_Skill) is a library of drop-in SKILL.md files for decompiling and source recovery.

Its workflow: install and register it with the agent, ask a question about a target, the agent calls the tools, and results come back as evidence records (an ID, the file's SHA-256 hash, the backend used and listed limitations) that you check before building.

**How Tahany uses it**

- Study only what you may study: your own apps and permissively licensed apps. Use REA to learn which Android permissions, services and APIs a reference app relies on, and how it copes with background limits. Then write Tahany's code yourself.
- Do not decompile Honor Connect or other Honor or Xiaomi system apps to copy them. Their terms often forbid it, copied code or icons can infringe copyright, and the features that matter rely on system privileges an installed app does not get. Tahany is built from public Android APIs.
- GPL projects such as droplet are fine to learn from, but copying their code would make Tahany GPL too.
- Install REA only from the official repositories and pin a version: fake copies appeared after it went viral.
- REA is not installed in this chat, so the plan below is written without running it.

## Features and what is possible

Eight of the nine features work with public Android APIs; call forwarding works only in part, because Android gives apps no way to carry call audio.

| Feature | Direction | Android building block | Possible? |
| --- | --- | --- | --- |
| Pairing by code | Both | Nearby Connections (advertise, discover, accept code) | Yes |
| File transfer | Both | Nearby file payloads | Yes |
| Clipboard sync | Both | ClipboardManager, foreground or manual send (Android 10+ blocks background reads) | Yes, with a manual step |
| Notification mirroring | Phone to tablet, optional reverse | NotificationListenerService, reply through the notification's own reply action | Yes, user grants notification access once |
| Phone screen on the tablet | Phone to tablet | MediaProjection, MediaCodec H.264, stream over the link | Yes, consent dialog each session |
| Touch control of the phone | Tablet to phone | AccessibilityService gestures | Yes, hardest and riskiest, last |
| Call forwarding | Phone to tablet | TelephonyCallback, TelecomManager answer and end | Partly: alert, caller ID, answer, decline; no call audio on the tablet |
| Screenshot on the tablet sent to the phone | Tablet to phone | ContentObserver on MediaStore, Nearby file payload | Yes |
| Screenshot on the phone sent to the tablet | Phone to tablet | Same, mirrored | Yes |

**Call forwarding limits.** The tablet can ring, show the caller and offer answer, decline and speakerphone buttons that act on the phone. Standard Android blocks apps from recording call audio, so the voice itself stays on the phone (speaker or Bluetooth headset). Honor's own call transfer uses system services Tahany cannot reach. This is my assessment from Android's design, not something I could verify against a source.

## Architecture

Tahany is one APK with two roles; each device runs the same five services and one encrypted Nearby Connections channel carries everything between them.

&#91;embedded content: Tahany architecture · 2 roles, 5 services, 1 link\]

Only screen mirroring is one-way; notifications (replies), calls (commands), screenshots and clipboard or files all travel both ways.

## How each feature works

Permission names below come from my knowledge of Android and should be checked against the current manifest documentation when you build.

**Pairing and the control channel**

- One side advertises, the other discovers; both show a short code and the user confirms it matches.
- Small control messages (JSON under 32 KB: hello, clipboard, notification, call state, screenshot notice) travel as byte payloads; files and video use file and stream payloads.
- Permissions: Bluetooth scan, advertise and connect, nearby Wi-Fi devices (Android 13+), location (Android 12 and below).

**Notification mirroring**

- The phone runs a NotificationListenerService, copies title, text and app icon of each new notification, and sends it to the tablet.
- Replies and action buttons go back to the phone and fire the original notification action.
- Per-app filter and a do-not-mirror list for banking and password apps.

**Call forwarding**

- The phone listens for call state with TelephonyCallback and sends ringing, answered and ended events with the caller's number and contact name.
- The tablet shows a full-screen incoming call card with Answer, Decline and Speaker; these send commands that the phone runs with TelecomManager.
- Permissions: read phone state, read call log (for the number), answer phone calls, read contacts.
- Audio stays on the phone. This is a limit of Android, not a choice.

**Phone screen on the tablet**

- The phone asks consent through MediaProjection each session and runs a foreground service of type mediaProjection (required from Android 14).
- Frames are encoded as H.264 with MediaCodec at 720p to 1080p and 30 fps, adaptive bitrate, and sent as a stream; the tablet decodes to a SurfaceView.
- Optional touch control sends tap and swipe events back; the phone injects them through an AccessibilityService the user must switch on.

**Automatic screenshot sync, both ways**

- Each device watches MediaStore for a new image whose path contains Screenshots, then sends it as a file payload to the other.
- The receiver saves it to a Tahany folder in Pictures and records its SHA-256, so it never sends the same file back. This stops loops.
- Settings: auto-send on or off, Wi-Fi only, and a notification with a Copy and Open button when one arrives.
- Permission: read media images (Android 13+).

**Clipboard and files**

- Clipboard: send-now button and a quick-settings tile; auto-sync only while the app is in the foreground.
- Files: share-sheet target on both devices, progress notification, resume by re-sending on failure.

## Redmi and Honor background limits

The biggest real-world risk is the Redmi killing Tahany in the background, which silently stops notification, call and screenshot sync.

- Xiaomi, Redmi and POCO phones on HyperOS or MIUI stop apps unless Autostart is on, battery is set to No restrictions and the app is locked in Recents.
- Android gives apps no public way to switch Autostart on. Tahany can only open the right settings screen and show the user a step-by-step guide on first run, with a check that warns when the service has been stopped.
- One forum report says HyperOS 3 limits background Bluetooth LE for third-party apps, so keep the live link on Wi-Fi once paired and use Bluetooth only to find the other device.
- Run the connection in a foreground service with a visible notification, and restart it on boot and when the app is reopened.
- The Honor tablet has its own battery manager (from my own knowledge, not a searched source); add the same guide for it and test it.
- Sideloading: both devices need Install unknown apps enabled for whichever app opens the APK, and Play Protect may ask for a scan.

## Build roadmap and risks

Build in four releases and do not start the next until the gate before it passes on the real Redmi and the real Honor Pad X9a.

&#91;embedded content: Tahany roadmap · 4 releases, 4 gates\]

Bands are in build order, not to scale: I have no dates from you, so none are invented. Tell me your weekly hours and I will add them.

**Biggest risks, in order**

1. The Redmi stops the service in the background. Fix: foreground service, the first-run settings guide, and gate 1 tested with the screen off.
2. Call forwarding disappoints: the tablet rings but the voice stays on the phone. Fix: agree this limit before building v3.
3. Screen mirroring lag on the phone's chip. Fix: start at 720p, raise only if it holds.
4. Copying code from a GPL or proprietary app. Fix: write Tahany from public Android APIs and use REA for study only.

**Test plan:** one Redmi and one Honor Pad X9a, tested on home Wi-Fi, on the phone's hotspot and with the screen off for ten minutes; log every dropped link.

## Reference app: Honor Connect

Honor Connect is the original; Tahany matches its core jobs but not its system-level features, and signs in by pairing code instead of a HONOR ID.

| Honor Connect feature | What Honor says it does | Tahany equivalent | Gap |
| --- | --- | --- | --- |
| Screen sharing | Cast a screen or drag app windows between devices | v4: phone screen on the tablet | No window dragging |
| Connected notifications | Phone alerts follow you to tablet, watch and laptop | v2: notification mirroring and replies | Tablet only, no watch or laptop |
| Connected calls | Incoming calls move across devices and can be answered on any | v3: call card with answer, decline, speaker | No call audio on the tablet |
| Cross-brand file transfer | Honor joined the Mutual Transfer Alliance for Xiaomi, OPPO and vivo, with a transfer switch on both devices | v1: file transfer and screenshot sync | Tahany works only between devices that both run it |
| Three-finger swipe-up | Cast, connect apps and send files from any screen | Quick-settings tile and a floating button | No system gesture |
| Connected input | Share a keyboard and mouse across devices | v4: touch control last | Touch only |
| HONOR ID sign-in | Features follow your account across Honor devices | Pairing by short code | No account sync |
| Network sharing, connected camera | Use the phone's network or camera on another device | Not planned | Out of scope |

Honor says many of these features depend on the model and MagicOS version, so check which ones your Pad X9a actually has.

**How to use it as the reference**

- Use Honor Connect on the tablet where it works, write down how each feature behaves, and make Tahany match the behaviour you like.
- Use the Honor pages in Sources for the feature list; do not decompile the app (see the REA section).
- Treat each row's Gap as a known difference, not a bug.

## Sources

As of 2026-11-20. Statements marked as my own knowledge (Android permissions, call audio, Honor battery manager) are not from these pages.

- [Honor Pad X9a specifications](https://devicebeast.com/tablets/pad-x9a)
- [Honor Connect features and cross-brand transfer](https://www.honor.com/ph/tech/connect/)
- [Honor Connect and HONOR ID](https://www.honor.com/mea/tech/connect)
- [Honor AI Space: adding a device](https://www.honor.com/uk/support/content/en-us15824281)
- [Google Nearby Connections overview](https://developers.google.com/nearby/connections/)
- [Android WebRTC screen sharing guide (MediaProjection rules)](https://www.forasoft.com/blog/article/android-webrtc-screen-sharing)
- [ScreenStream, open-source MediaProjection example](https://github.com/dkrivoruchko/ScreenStream)
- [Xiaomi background app restrictions](https://safelagoon.crunch.help/help-center/for-xiaomi-devices)
- [Xiaomi Autostart has no public setter](https://coldfusion-example.blogspot.com/2026/03/how-to-programmatically-route-users-to.html)
- [HyperOS 3 Bluetooth LE background report](https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-mobile-andriod/443699/persistent-bluetooth-le-disconnection-between-fenix-6-pro-and-xiaomi-x15t-pro-hyperos-3-0-336-0)
- [REA explained, with setup and legal notes](https://codewithmukesh.com/blog/reverse-engineer-anything-rea/)
- [Reverse Engineer Anything overview](https://www.mindstudio.ai/blog/reverse-engineer-anything-github-tool)
