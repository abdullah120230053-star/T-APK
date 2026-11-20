# Repository Guidelines

Tahany: one Android app installed on two devices — the Honor Pad X9a and the Redmi (phone) — linking them over local Wi-Fi and Bluetooth. Two roles per device; five services (pairing, control, file transfer, notifications, screenshots, clipboard) behind one encrypted Nearby Connections link. See `Tahany — Honor Pad X9a ↔ Redmi Link App Plan.md` for the feature plan.

## Coding style

- **Language and UI:** Kotlin with Jetpack Compose; no XML view layer.
- **Minimum API level:** `minSdk = 26`.
- **APIs:** Public Android APIs only — no reflection into hidden or internal APIs.
- **Licensing:** Write all new code from public Android APIs. GPL or proprietary apps may be studied for behavior, but copying their code would make Tahany GPL — do not copy.

## Build, lint and install

Run builds and lint from the project root with `gradw assembleDebug` and `gradw lint` (`gradw` runs the Gradle 8.13 dist at `C:\Users\Hany-Tech\.gradle\wrapper\dists\gradle-8.13-bin\928fl043lon0uu06jzrrnc41m\gradle-8.13`).

- **JDK:** `JAVA_HOME` defaults to the Zulu JDK 17 at `D:\Skills\Remote\remotehub\.tooling\jdk\zulu17.70.113-ca-jdk17.0.21.0.3-win_x64` — the Temurin JDK 21 toolchain at `.toolchain\jdk` cannot load Gradle's native services.
- **SDK:** `D:\Skills\Remote\remotehub\.tooling\sdk` (set in `local.properties`).
- **Install:** Adb at `D:\Skills\Remote\remotehub\.tooling\sdk\platform-tools\adb.exe`; `adb install` the debug APK (`app\build\outputs\apk\debug\app-debug.apk`) on each device.
