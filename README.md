# Tahany

One Android app, two roles, one encrypted local link between an Honor Pad X9a
and a Redmi phone. Tahany is independent of Honor Connect: no HONOR ID, no
account, no cloud relay.

The feature plan lives in [`Tahany — Honor Pad X9a ↔ Redmi Link App Plan.md`](<Tahany — Honor Pad X9a ↔ Redmi Link App Plan.md>).
The concrete build backlog and release gates live in [`TASKS.md`](TASKS.md).

## What v1 does

- Choose **Phone** or **Tablet** on first run; the choice is stored locally.
- Host on one device, join on the other through Nearby Connections.
- Show the same six-digit short authentication string on both screens; no data
  moves until both users confirm it.
- Send files from either share sheet; received files go to
  `Pictures/Tahany` (images) or `Downloads/Tahany` (everything else).
- Send the clipboard with a button or the quick-settings tile. Automatic
  clipboard sync runs only while the app is in the foreground.
- Watch `Pictures/Screenshots` both ways and save incoming screenshots into
  `Pictures/Tahany`. A SHA-256 ledger stops echoes and duplicates.
- Keep the link in a `connectedDevice` foreground service, restart it after
  boot, and show a visible notification while it runs.
- Guide the user through Xiaomi/HyperOS Autostart and Honor battery settings.
  These are deep links with safe fallbacks; Android has no public setter for
  Autostart.

Notification mirroring, calls, and screen mirroring are deliberately not in
v1. Their tasks and gates are in `TASKS.md`.

## Build

Requirements: JDK 17+, Android SDK with platform 36 and build-tools 36.0.0.

The repository has a project-local SDK path in `local.properties` that is not
committed. On this machine the existing SDK is at:

```text
D:/Skills/Remote/remotehub/.tooling/sdk
```

Build the debug APK:

```powershell
$env:JAVA_HOME = 'D:\Skills\Remote\remotehub\.tooling\jdk\zulu17.70.113-ca-jdk17.0.21.0.3-win_x64'
$env:GRADLE_USER_HOME = 'D:\Skills\Tahany\.gradle-home'
$env:ANDROID_USER_HOME = 'D:\Skills\Tahany\.android-home'
.\gradlew.bat :app:assembleDebug --no-daemon --no-watch-fs
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests (protocol, pairing vector, screenshot loop prevention, trusted-peer
codec):

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-watch-fs
```

Install on a connected device:

```powershell
& 'D:\Skills\Remote\remotehub\.tooling\sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

## Architecture

```text
MainActivity / Compose
        |
        v
TahanyViewModel  ---- binds ----  TahanyService (foreground, connectedDevice)
                                      |
              +-----------------------+-----------------------+
              |                       |                       |
       LinkController          ClipboardSyncManager     ScreenshotWatcher
              |                       |                       |
       NearbyTransport          ClipboardManager        MediaStore observer
              |
       JSON control messages + Nearby file payloads
```

- `protocol/` — versioned sealed message schema, 32 KiB codec, SAS derivation.
- `link/` — Nearby adapter and the session state machine. Only a confirmed
  session is allowed to send files, clipboard, or screenshots.
- `service/` — foreground lifecycle, notification channels, file receive/save,
  boot receiver, quick-settings tile.
- `screenshot/` — MediaStore watcher, bounded SHA-256 ledger, loop guard.
- `files/` — scoped-storage save path (`Pictures/Tahany`) and share-sheet
  staging.
- `permissions/` — API-aware runtime permission list and OEM survival guide.

### Security notes

- Nearby Connections provides the encrypted channel. Tahany adds a six-digit
  SAS derived from both app-scoped device IDs and both fresh nonces, plus an
  HMAC proof, so a relay shows different codes on the two screens.
- Peer display names and filenames are sanitized before they touch the
  filesystem. Received files are checked against the offer's SHA-256 and size
  before they are published to MediaStore.
- The app-scoped device ID is a random UUID, not an IMEI, serial, or MAC.
- The manifest requests no contacts, call log, or phone-state permission in
  v1; those arrive with v3 after a separate review.

## Known limits

- **Google Play Services is required by Nearby Connections.** Gate 0 in
  `TASKS.md` checks the Honor Pad. If it is missing, the transport must be
  replaced with NSD + TLS before v1 can ship.
- **No Wi-Fi-only enforcement yet.** The preference is stored and shown, but
  Nearby chooses the physical route. Do not promise enforcement until a custom
  transport exists.
- **No call audio.** v3 can alert, answer, decline, and toggle speaker on the
  phone; Android gives third-party apps no path to carry the call audio.
- **No notification mirroring or screen mirroring in v1.** v2 and v4.
- **Not field-tested on the real Redmi/Honor pair yet.** Gate 1 in `TASKS.md`
  is the acceptance test, including ten minutes with the screen off.

## Legal

Tahany is written from public Android APIs. Honor Connect and Xiaomi system
apps are reference behaviour only; they are not decompiled or copied. GPL
projects may be studied, but their code is not copied into this repository.
