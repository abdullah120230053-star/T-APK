# Tahany setup

Install Tahany-0.2.1-debug.apk on both the Redmi and Honor Pad X9a. If Android prompts you, allow app installs from the file manager you used to open the APK.

1. Choose **Phone** on the Redmi and **Tablet** on the Honor Pad.
2. Enable Google Play services, Wi-Fi, and Bluetooth on both devices and grant Nearby access.
3. Tap **Host** on one device and **Join** on the other. Compare the six-digit code and confirm on both screens.

If the red alert returns, note the Nearby number shown in the alert. On Android 12 or earlier, also check that Location is on and that Tahany has the location access it requests.

For USB installation from the project computer, run the following from this folder. Use the serial from `adb devices` and repeat for the other device.

```powershell
.\install.ps1 -Serial <device-serial>
```

This is the debug APK. See [TASKS.md](../TASKS.md) for real-device acceptance checks.
