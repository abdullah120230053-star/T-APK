# Tahany — build tasks and release gates

Status: **v1–v4 implemented; build, unit tests and lint are green. Real-device gates (T0.2, T1.20, T2.5, T3.5, T4.5) are still open — no devices attached yet.** Scope source: [`Tahany — Honor Pad X9a ↔ Redmi Link App Plan.md`](<Tahany — Honor Pad X9a ↔ Redmi Link App Plan.md>).

Tahany is one APK with two roles. The same code runs on the Honor Pad X9a
(Android 15 / API 35) and the Redmi (MIUI or HyperOS, Android version still
unknown). Per `AGENTS.md`, minimum supported version is Android 8.0 (API 26),
compile/target SDK 36, so the Redmi can be anywhere from Android 8 upward.
Android 8–9 use the legacy storage path and the Android 12L-and-below location
permission; both are handled in code.

## Decisions that shape the build

| Decision | Choice | Why |
| --- | --- | --- |
| UI | Kotlin + Jetpack Compose, Material 3 | One codebase for phone and tablet, no XML duplication |
| Link | Google Nearby Connections 19.5.1, point-to-point strategy | Encrypted transport, bytes + file payloads, no server or account |
| Discovery fallback | Not in v1. Gate 0 checks Google Play Services on both devices | Nearby needs GMS. If either device lacks it, v1 swaps the transport for NSD + TLS sockets before feature work continues |
| Identity | Random app-scoped UUID, not a hardware ID | Survives reinstall only if backed up; no IMEI/serial collection |
| Pairing | Both sides exchange nonces, derive a 6-digit short authentication string (SAS), user compares it | Nearby encrypts the channel; the SAS catches a relay/MITM |
| Control messages | JSON with a `type` discriminator, hard 32 KiB cap | Easy to inspect, testable without Android |
| Large data | Nearby file payloads; v4 will use stream payloads | No custom chunking in v1 |
| Screenshot loop prevention | Watch only `.../Screenshots`; save received files to `Pictures/Tahany`; SHA-256 ledger | The receiver's own writes are outside the watched path, and the ledger stops echoes and duplicates |
| Call audio | Not attempted | Android gives third-party apps no call-audio capture path; v3 is alert/answer/decline/speaker commands only |
| Proprietary code | Never decompiled or copied | Honor Connect and Xiaomi system apps are reference behaviour only; Tahany uses public APIs |

## Phase 0 — foundation and a real go/no-go check

- [x] **T0.1 Toolchain** — project-local JDK 21, Android SDK 36, Gradle wrapper; `assembleDebug` succeeds from a clean checkout.
- [ ] **T0.2 Gate 0, real devices** — confirm Google Play Services is present on both the Honor Pad X9a and the Redmi, and that Nearby Connections can advertise/discover with the screen off for 10 minutes. If this fails, stop feature work and replace the transport with NSD + TLS before v1 continues.
- [x] **T0.3 Skeleton** — package `com.dama.tahany`, role selection persisted, foreground service starts on launch and after boot, notification channel visible.
- [x] **T0.4 Permissions** — runtime request flow for Bluetooth scan/advertise/connect, nearby Wi-Fi, notifications, and media images; Android 12-and-below location path kept.
- [x] **T0.5 OEM survival guide** — first-run guide for Xiaomi Autostart/battery/Recents and Honor battery manager; deep links fall back safely; service-stopped warning.

## Phase 1 — v1: pairing, files, clipboard, screenshots

### Protocol and pairing

- [x] **T1.1 Message schema** — sealed `TahanyMessage` types for hello, SAS confirm, clipboard, file offer/result, screenshot notice, ping/pong, error; version field; unknown-type rejection.
- [x] **T1.2 Codec** — encode/decode JSON, 32 KiB limit on both sides, malformed input never crashes the service.
- [x] **T1.3 SAS** — deterministic six-digit code from both device IDs and both nonces; constant-time proof check; unit tests with fixed vectors.
- [x] **T1.4 Trusted peers** — store peer device ID + display name only after the user confirms the code; subsequent sessions may auto-confirm a known ID, with a "forget" action.
- [x] **T1.5 Handshake** — hello/hello-ack, code display on both screens, accept/reject, timeout, clean disconnect.

### Link service

- [x] **T1.6 Foreground service** — `connectedDevice|dataSync`, visible notification with peer/state, stop action, restart on boot.
- [x] **T1.7 Nearby transport** — host advertises, join discovers, accept first connection, payload routing, transfer progress, disconnect/reconnect with backoff.
- [x] **T1.8 Link state** — a single `StateFlow` the UI observes: idle, advertising, discovering, handshake, connected, transferring, error.
- [x] **T1.9 Permission-denied behaviour** — explicit message and a button that opens the right settings screen; no crash loops.

### Files and clipboard

- [x] **T1.10 Share sheet** — `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for any file type; stage to cache, send one at a time, clean up.
- [x] **T1.11 Receive files** — verify size and SHA-256, save images to `Pictures/Tahany` and other files to `Downloads/Tahany`, progress + result notification.
- [x] **T1.12 Clipboard send-now** — foreground read, 32 KiB cap, explicit "sent" feedback.
- [x] **T1.13 Clipboard auto-sync** — only while the app is in the foreground, hash-compare to avoid echo, never read in the background.
- [x] **T1.14 Quick-settings tile** — sends the clipboard through the running service; disabled state is clear.

### Screenshots both ways

- [x] **T1.15 Watcher** — MediaStore observer, `Screenshots` path filter, newest-first query, debounce until the file is complete, permission-aware.
- [x] **T1.16 Save + ledger** — insert into `Pictures/Tahany`, publish with `IS_PENDING`, record SHA-256, cap the ledger, never re-send a received hash.
- [x] **T1.17 Loop tests** — pure-JVM tests for self-echo, A→B→A, duplicate delivery, same hash different filename, and ledger pruning.
- [x] **T1.18 Settings** — auto-send on/off, Wi-Fi-only flag (stored and surfaced; Nearby already prefers Wi-Fi), incoming notification with Copy and Open.

### v1 tests and gate

- [x] **T1.19 Unit tests** — protocol round-trip, oversized message, malformed JSON, SAS vectors, dedup/loop prevention, trusted-peer parsing.
- [ ] **T1.20 Gate 1, real devices** — pair by code; send a 50 MB file each way; clipboard both ways; screenshot each way; screen off 10 minutes; log every drop with timestamps and state.

## Phase 2 — v2: notifications

- [x] **T2.1 NotificationListenerService** — user grants access once; mirror title, text, app label, small icon, timestamp, key.
- [x] **T2.2 Tablet notification** — local notification with reply action; tap opens details or inline reply directly.
- [x] **T2.3 Replies** — find the notification's `RemoteInput` action, send text back over the control channel, fire the original `PendingIntent` with the result bundle; report failures.
- [x] **T2.4 Per-app filter** — allow/deny list stored locally on the phone; defaults deny banking, password managers, and system security apps.
- [ ] **T2.5 v2 gate** — reply from the tablet to WhatsApp/SMS on the Redmi; filtered app never leaves the phone; listener survives 10 minutes screen-off.

## Phase 3 — v3: calls

- [x] **T3.1 Call monitor** — `TelephonyCallback.CallStateListener` + `READ_PHONE_STATE`; emit ringing/answered/ended with number and contact name when permissions allow.
- [x] **T3.2 Tablet call card** — full-screen incoming card with Answer, Decline, Speaker; prominent note that call audio stays on phone.
- [x] **T3.3 Commands** — `TelecomManager.acceptRingingCall()`, `endCall()`, speaker toggle via `AudioManager` on the phone; every command reports success/failure.
- [x] **T3.4 Permission fallback** — if `ANSWER_PHONE_CALLS` or call-log/contacts access is denied, the card still shows what is available and the buttons explain what is missing.
- [ ] **T3.5 v3 gate** — real incoming call: tablet rings, caller ID matches, answer/decline/speaker each work on the phone, and the documented "no audio on tablet" limit is accepted before release.

## Phase 4 — v4: screen mirroring and touch

- [x] **T4.1 Capture** — MediaProjection consent per session, foreground service type `mediaProjection`, 720p/30 fps first.
- [x] **T4.2 Encode/decode** — MediaCodec H.264, SurfaceView on the tablet, adaptive bitrate starting at 2.5 Mbps.
- [x] **T4.3 Stream transport** — Nearby stream framing, backpressure, keyframe request on join, clean teardown.
- [x] **T4.4 Touch (last)** — optional AccessibilityService on the phone, tap/swipe injection via `TahanyAccessibilityService`.
- [ ] **T4.5 v4 gate** — 30 minutes of mirroring on home Wi-Fi and the Redmi hotspot; measure latency, dropped frames, battery, and thermal throttling before calling it done.

## Cross-cutting backlog

- [ ] **X1 Security review** — no secrets in logs, no exported components without permission, validate every inbound path/name/size, cap memory, reject path traversal.
- [ ] **X2 Reliability** — reconnect backoff, transfer resume by re-send, service restart, boot receiver, "last seen" timestamp.
- [ ] **X3 Accessibility** — content descriptions, large-screen layout for the Pad, dark theme, 48 dp targets.
- [ ] **X4 Release** — signed release build, versionName/versionCode, privacy note (no analytics, no internet permission unless needed), sideload instructions for MIUI/HyperOS and MagicOS.
- [ ] **X5 Field log** — one row per test: date, devices, transport, duration, drops, battery, notes. Gate decisions cite this log.

## Open questions (answer before the phase that needs them)

1. **Redmi model and Android version** — needed for the exact permission and Autostart screens. v1 supports Android 10+ regardless.
2. **Google Play Services on the Honor Pad X9a** — Gate 0. If absent, Nearby is not viable and the transport changes.
3. **"Album include all"** — assumed: received screenshots go to `Pictures/Tahany`; the device's own screenshots stay in `Pictures/Screenshots`. If every screenshot should be copied into one album locally, that is a separate v1.5 task.
4. **Wi-Fi only** — Nearby chooses its own transport. The setting is recorded and shown, but v1 cannot force Wi-Fi-only without a custom transport; do not promise enforcement until the fallback exists.
5. **Call-audio expectation** — v3 must be accepted as alert/control only before it is built, or it will be judged against a feature Android does not allow.

## Definition of done for v1

A debug APK installs on both devices, the user picks a role, grants the listed
permissions, hosts on one device and joins on the other, compares the six-digit
code, and then: a file sent from either share sheet arrives intact; clipboard
send-now works both ways; a new screenshot on either device appears in
`Pictures/Tahany` on the other exactly once; the foreground notification stays
alive with the screen off for ten minutes. Unit tests pass, and the Gate 1 log
is filled in.
