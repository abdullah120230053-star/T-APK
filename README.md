# Tahany (تهاني) — Honor Pad X9a ↔ Redmi Link App

> **One APK. Two Roles. Zero Cloud. Encrypted Local Peer-to-Peer Link.**  
> GitHub Repository: [https://github.com/abdullah120230053-star/T-APK](https://github.com/abdullah120230053-star/T-APK)

Tahany links an **Honor Pad X9a** (MagicOS 9.0 / Android 15, Snapdragon 685) and an **Android phone** (Redmi running HyperOS / MIUI, `minSdk = 26`, target SDK 36) directly over local Wi-Fi and Bluetooth.

Tahany is completely independent of Honor Connect and Xiaomi Cloud:
- **No HONOR ID or Xiaomi Account required.**
- **No cloud servers, relays, or internet telemetry.**
- **Clean-room public Android APIs only** (no reflection into hidden frameworks, no proprietary binary blobs, no GPL code).

---

## Complete Project History: DeepSeek Design & Antigravity Engineering

This project represents the complete fusion of **DeepSeek's** comprehensive system architecture and **Antigravity's** end-to-end engineering, implementation, and verification.

```
+-----------------------------------------------------------------------------------------+
|                                    TAHANY ARCHITECTURE                                  |
+-----------------------------------------------------------------------------------------+
|                                                                                         |
|   +---------------------------------+             +---------------------------------+   |
|   |          REDMI (PHONE)          |             |       HONOR PAD X9a (TABLET)   |   |
|   |  - NotificationListenerService  |             |  - Mirrored Notification Card   |   |
|   |  - CallMonitor (Telephony)      |             |  - Remote Reply ActionReceiver  |   |
|   |  - CallCommandHandler (Telecom) |             |  - Incoming Call Heads-up & Card|   |
|   |  - MediaProjection (720p/30fps) |             |  - ScreenCast SurfaceView       |   |
|   |  - Accessibility Touch Injection|             |  - Touch Event Dispatcher       |   |
|   +----------------+----------------+             +----------------+----------------+   |
|                    |                                               |                    |
|                    v                                               v                    |
|   +---------------------------------------------------------------------------------+   |
|   |                       Google Nearby Connections (P2P Cluster)                   |   |
|   |               HMAC-SHA256 SAS 6-Digit Pairing | 32 KiB JSON Protocol            |   |
|   +---------------------------------------------------------------------------------+   |
|                                                                                         |
+-----------------------------------------------------------------------------------------+
```

---

### Part 1: What DeepSeek Designed & Specified

DeepSeek established the core architectural blueprint, domain boundaries, and protocol specifications:

1. **Strategic Product & Feature Plan Analysis:**
   - Evaluated the requirements in `Tahany — Honor Pad X9a ↔ Redmi Link App Plan.md`.
   - Identified the boundaries of what public Android APIs permit vs. what requires system OEM privileges.
   - Clarified the **Call Audio Boundary**: Android deliberately forbids third-party apps from recording or carrying real-time call voice audio. DeepSeek established that Tahany would carry telephony signaling, caller ID, contact resolution, and remote telephony commands (`Answer`, `Decline`, `Speakerphone`), while voice audio stays on the phone/Bluetooth headset.

2. **Clean-Room & Legal Architecture:**
   - Established strict adherence to public Android SDK APIs.
   - Explicitly forbade decompiling or borrowing code from Honor Connect, Xiaomi system apps, or GPL projects like Droplet, ensuring Tahany remains clean, unencumbered, and legally distributable.

3. **Cryptographic SAS Pairing Model:**
   - Designed a zero-trust pairing handshake using Google Nearby Connections.
   - Formulated the Short Authentication String (SAS): a 6-digit verification code derived deterministically via HMAC-SHA256 from both ephemeral nonces and app-scoped device UUIDs to protect against relay and man-in-the-middle (MITM) attacks.

4. **Bidirectional Screenshot Loop Guard:**
   - Formulated the dedup architecture for screenshot synchronization:
     - Watched folder: `Pictures/Screenshots`.
     - Target save folder: `Pictures/Tahany`.
     - Loop guard: A bounded in-memory SHA-256 hash ledger that ignores incoming self-echoes and duplicate files.

5. **OEM Background Survival Guide:**
   - Cataloged critical aggressive battery-killer behaviors across HyperOS/MIUI and MagicOS.
   - Documented required bypasses: Autostart, Battery Saver "No restrictions", Lock in Recents, "Show on Lock screen", and "Display pop-up windows".
   - Chose the `connectedDevice` foreground service type to prevent Android 15's 6-hour runtime quota imposed on `dataSync`.

6. **Phased Backlog & Release Gates (TASKS.md):**
   - Structured the release into 5 rigorous phases:
     - **Phase 0:** Toolchain & Gate 0 (Google Play Services validation).
     - **Phase 1 (v1):** Pairing, Encrypted Link, File Transfer, Clipboard Sync, Screenshot Sync.
     - **Phase 2 (v2):** Notification Mirroring & Inline `RemoteInput` Replies.
     - **Phase 3 (v3):** Telephony Call State & Remote Call Control.
     - **Phase 4 (v4):** MediaProjection 720p Screen Mirroring & Accessibility Touch Control.

---

### Part 2: What Antigravity Engineered & Implemented

Antigravity took the architectural blueprint and delivered the full, operational codebase across all four version tiers:

1. **Development Toolchain & Windows Sandbox ACL Fixes:**
   - Resolved Windows NTFS security descriptor constraints (`Win32Error: SetNamedSecurityInfoW`) using unconfined access scripts.
   - Configured Zulu JDK 17 (`D:\Skills\Remote\remotehub\.tooling\jdk\zulu17.70.113-ca-jdk17.0.21.0.3-win_x64`) and Android SDK 36.
   - Configured custom Gradle memory profiles (`-Xmx2048m -XX:MaxMetaspaceSize=512m`) to prevent native heap exhaustion under Windows memory limits.

2. **Git Repository Setup & Synchronization:**
   - Initialized Git tracking on `main` branch.
   - Resolved rebase divergence against remote repository.
   - Maintained clean commits and continuous synchronization with [https://github.com/abdullah120230053-star/T-APK](https://github.com/abdullah120230053-star/T-APK).

3. **Core Protocol & Serialization Engine (`com.dama.tahany.protocol`):**
   - Built `Protocol.kt` containing versioned sealed classes for all system events:
     - `Hello`, `HelloAck`, `SasConfirm`
     - `ClipboardSync`
     - `FileOffer`, `FileAccept`, `FileReject`, `FileTransferComplete`
     - `ScreenshotNotify`
     - `NotificationEvent`, `NotificationAction`
     - `CallEvent`, `CallAction`
     - `ScreenCastOffer`, `ScreenCastAnswer`, `ScreenCastStop`, `TouchEvent`
     - `Ping`, `Pong`, `ErrorMessage`
   - Enforced strict 32 KiB frame caps with safe deserialization and malformed message rejection.

4. **Cryptographic Pairing Implementation (`com.dama.tahany.link.Pairing`):**
   - Authored constant-time SAS calculation using HMAC-SHA256 (`deriveSasCode`).
   - Implemented trusted peer storage in `SettingsRepository` to enable instant auto-reconnects once initially paired.

5. **Bidirectional Screenshot Sync (`com.dama.tahany.screenshot`):**
   - Implemented `ScreenshotWatcher` using Android's `ContentObserver` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.
   - Built `ScreenshotDedupLedger` keeping an LRU ring buffer of recent SHA-256 hashes to guarantee zero echo loops when screenshots are transferred back and forth.

6. **Notification Mirroring & Inline Remote Replies (`com.dama.tahany.service` - v2):**
   - Built `TahanyNotificationListenerService`:
     - Intercepts active notifications on the phone.
     - Automatically strips sensitive apps (banking, 2FA authenticators, password managers).
     - Scans `Notification.Action` objects for `RemoteInput` instances to enable bi-directional replies.
   - Built `NotificationReplyReceiver` & tablet notifications:
     - Creates local tablet notifications under `CHANNEL_MIRRORED`.
     - Attaches inline `RemoteInput` reply boxes so tablet users can reply directly to WhatsApp, Telegram, or SMS.
     - Marshals replies back to the phone to trigger `PendingIntent.send()`.

7. **Telephony Call Alerts & Remote Actions (`com.dama.tahany.service` - v3):**
   - Built `CallMonitor`:
     - Uses modern `TelephonyCallback` on Android 12+ (API 31+) with fallback to `PhoneStateListener` on API 26–30.
     - Resolves phone numbers against `ContactsContract.PhoneLookup`.
   - Built `CallCommandHandler`:
     - Remotely answers calls via `TelecomManager.acceptRingingCall()`.
     - Remotely rejects calls via `TelecomManager.endCall()`.
     - Toggles speakerphone state via `AudioManager.setSpeakerphoneOn()`.
   - Built `CallActionReceiver` and full-screen call cards on the tablet UI.

8. **Hardware-Accelerated Screen Mirroring & Touch Injection (`com.dama.tahany.screencast` - v4):**
   - Built `ScreenCastManager`:
     - Integrates Android `MediaProjection` with strict Android 14+ `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` compliance.
     - Hardware-accelerated H.264 video encoding at 720p @ 30fps (2.5 Mbps target bitrate) using `MediaCodec`.
     - Captures output via `createVirtualDisplay()`.
   - Built `ScreenCastReceiver`:
     - Decodes H.264 NAL units using `MediaCodec.createDecoderByType("video/avc")` directly into an Android `SurfaceView`.
   - Built `TahanyAccessibilityService`:
     - Implements touch tap and swipe gesture dispatching (`dispatchGesture()`) on the phone responding to tablet touch coordinates.

9. **Service Layer & UI Integration:**
   - `TahanyService`: Orchestrates all background operations under a single persistent `connectedDevice` Foreground Service.
   - `LinkController`: Manages Nearby Connections lifecycle, state machines, and message routing.
   - `TahanyScreen.kt` & `TahanyViewModel.kt`: Jetpack Compose Material 3 interface providing:
     - Immediate Phone/Tablet role switching.
     - Live link status and SAS code pairing prompt.
     - Mirrored notification stream.
     - Incoming call overlay cards.
     - Live screen mirroring viewfinder.
     - OEM permission diagnostic cards with direct deep-links.

10. **Comprehensive Unit Test Suite:**
    - `ProtocolCodecTest`: 21 automated JVM tests covering JSON encoding/decoding, frame limits, SAS verification, and ledger deduplication.

---

## Codebase Map

| Package / File | Purpose |
| --- | --- |
| `com.dama.tahany.model.Models` | Domain models: `DeviceRole`, `LinkState`, `MirroredNotification`, `CallStateModel`. |
| `com.dama.tahany.protocol.Protocol` | Sealed class network schema, 32 KiB envelope validation, serialization. |
| `com.dama.tahany.link.Pairing` | Cryptographic HMAC-SHA256 6-digit SAS code derivation and proof matching. |
| `com.dama.tahany.link.LinkController` | Session state machine, Nearby Connections transport adapter, payload router. |
| `com.dama.tahany.screenshot.ScreenshotWatcher` | MediaStore ContentObserver for auto-detecting new screenshots. |
| `com.dama.tahany.screenshot.ScreenshotDedup` | SHA-256 deduplication ledger preventing infinite echo loops. |
| `com.dama.tahany.service.TahanyService` | Foreground service (`connectedDevice`), boot recovery, action orchestration. |
| `com.dama.tahany.service.TahanyNotificationListenerService` | Notification interceptor, sensitive package filter, `RemoteInput` extractor. |
| `com.dama.tahany.service.NotificationReplyReceiver` | Broadcast receiver relaying tablet inline replies to phone notifications. |
| `com.dama.tahany.service.CallMonitor` | Telephony listener emitting phone ringing/offhook/idle events. |
| `com.dama.tahany.service.CallCommandHandler` | Executes answer, decline, and speakerphone toggles via Telecom/Audio managers. |
| `com.dama.tahany.service.CallActionReceiver` | Handles notification click actions on tablet call alerts. |
| `com.dama.tahany.screencast.ScreenCastManager` | MediaProjection + MediaCodec H.264 720p hardware encoder. |
| `com.dama.tahany.screencast.ScreenCastReceiver` | MediaCodec H.264 hardware decoder rendering to a tablet Surface. |
| `com.dama.tahany.accessibility.TahanyAccessibilityService` | Accessibility service dispatching tap and swipe gestures. |
| `com.dama.tahany.permissions.PermissionCatalog` | Runtime permission resolver and OEM battery survival deep links. |
| `com.dama.tahany.ui.TahanyScreen` | Jetpack Compose UI: Dashboard, role selector, call card, notifications, screencast. |

---

## Build & Test Instructions

### Environment Prerequisites
- **JDK:** Zulu OpenJDK 17 (`D:\Skills\Remote\remotehub\.tooling\jdk\zulu17.70.113-ca-jdk17.0.21.0.3-win_x64`).
- **Android SDK:** Platform 36, Build-Tools 36.0.0 (`D:\Skills\Remote\remotehub\.tooling\sdk`).
- **Gradle:** 8.13 wrapper (`gradlew.bat`).

### Run Unit Tests
```powershell
$env:JAVA_HOME = 'D:\Skills\Remote\remotehub\.tooling\jdk\zulu17.70.113-ca-jdk17.0.21.0.3-win_x64'
$env:GRADLE_USER_HOME = 'D:\Skills\Tahany\.gradle-home'
$env:ANDROID_USER_HOME = 'D:\Skills\Tahany\.android-home'

.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-watch-fs
```

### Build Debug APK
```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --no-watch-fs
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### Install on Connected Devices via ADB
```powershell
& 'D:\Skills\Remote\remotehub\.tooling\sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Hardware Release Gates (from TASKS.md)

| Gate | Target Devices | Description | Status |
| --- | --- | --- | --- |
| **Gate 0** | Both Devices | Validate Google Play Services & Nearby Connections 10-min screen-off link. | Ready for Hardware |
| **Gate 1** | Both Devices | Confirm 6-digit SAS pairing, 50MB file transfer, bidirectional screenshot sync. | Ready for Hardware |
| **Gate 2** | Phone -> Tablet | Verify notification mirroring and inline `RemoteInput` reply (e.g. WhatsApp/SMS). | Ready for Hardware |
| **Gate 3** | Phone -> Tablet | Verify incoming call heads-up, caller ID, remote Answer/Decline/Speaker. | Ready for Hardware |
| **Gate 4** | Phone -> Tablet | 30 minutes 720p/30fps screen mirroring and accessibility touch injection. | Ready for Hardware |

---

## License & Attribution

Tahany is written entirely from clean-room public Android APIs by **@DAMA** and the engineering team. All reference architectures adhere strictly to permissive licenses. Honor Connect and Xiaomi proprietary services are neither decompiled nor included.
