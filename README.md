# Tahany

Tahany connects an Honor Pad X9a and a Redmi phone over an encrypted local Nearby Connections session. Install the same APK on both devices, choose **Phone** on the Redmi and **Tablet** on the Honor Pad, then **Host** on one and **Join** on the other. Compare the six-digit code on both screens whenever you connect.

## Features

- **Link** shows the connection, file and clipboard actions, and phone screen mirroring. Screen sharing asks for Android consent each session. Optional remote touch requires the Tahany accessibility service on the phone.
- **Activity** shows the latest transfer and, on the tablet, mirrored phone notifications with inline reply when the original notification supports it.
- **Settings** contains the device role, screenshot sync, runtime permissions, notification filtering, and background settings for MagicOS and MIUI/HyperOS.
- **Screenshots** taken after screenshot sync is enabled are collected in `Pictures/Tahany` alongside received screenshots. Android keeps the originals in their usual location. Screenshots taken while connected are also sent to the other device.
- **Files** are verified before saving to `Pictures/Tahany` (images) or `Downloads/Tahany` (other files). Shares from other apps ask for confirmation before sending.
- **Calls** appear on the tablet when the phone permits call monitoring. Available controls act on the phone. Voice audio stays on the phone or its connected headset.

## Build and install

Use JDK 17, Android SDK platform 36, and Gradle 8.13. Set `sdk.dir` in a local `local.properties` or configure the SDK through your environment.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lint
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

On the project Windows machine, `.\gradw.bat` uses the preinstalled Gradle distribution and defaults to the Zulu JDK 17 path in [AGENTS.md](AGENTS.md). Repeat installation for the other device. Install the current build on both devices to get the same pairing behavior. See the [setup guide](setup/README.md) for installation and retry steps.

## Hardware acceptance

Google Play services must be enabled on both devices. Nearby chooses the transport. On Android versions that restrict clipboard reads, open Tahany and use **Clipboard** to send manually. The Quick Settings tile opens the app.

Check [TASKS.md](TASKS.md) for the real-device gates, including background operation, transfers over Wi-Fi and hotspot, call controls, notifications, and sustained screen mirroring. Release builds require the project owner's Android signing key.

Tahany uses public Android APIs and has no Tahany account, cloud relay, or analytics service. Pairing uses the Nearby session authentication token to bind the compared code. [Nearby Connections data exchange](https://developers.google.com/nearby/connections/android/exchange-data) · [Android media projection](https://developer.android.com/media/grow/media-projection)

## HONOR Connect feature coverage

| HONOR Connect feature | Tahany on Redmi and Honor Pad |
| --- | --- |
| File transfer | Bidirectional file sharing between devices running Tahany |
| Connected notifications | Phone notifications and supported inline replies on the tablet |
| Screen sharing and connected input | Phone screen mirroring and optional remote touch. Keyboard and mouse sharing, drag-and-drop, and moving app windows are outside current coverage. |
| Connected calls | Call alerts and available phone-side controls. Voice audio stays on the phone or headset. |
| Connected apps, camera, network sharing, Global Favorites, and system gestures | Outside current coverage. HONOR's original integrations rely on supported devices and system features. |

See [HONOR Connect's feature list](https://www.honor.com/ph/tech/connect/) and [Tahany's hardware acceptance gates](TASKS.md).


## Project history

DeepSeek designed the architecture and release gates. Gemini through Antigravity implemented the initial Android app. OpenCode stabilized the build and pushed the original implementation. See the [OpenCode handoff](HANDOFF.md) for that session.
