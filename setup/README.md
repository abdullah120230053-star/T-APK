# Tahany setup

Install Tahany-0.3.0-debug.apk on both the Redmi and Honor Pad X9a. If Android prompts you, allow app installs from the file manager you used to open the APK.

1. Choose **Phone** on the Redmi and **Tablet** on the Honor Pad.
2. Connect both devices to the same Wi-Fi network. Bluetooth can stay off.
3. Tap **Host** on one device, then **Join** on the other. Type the full address shown by the host, including its port, and tap **Connect**.
4. Compare the six-digit code and confirm on both screens only if it matches.

If the host cannot be reached, verify the full address and try a regular Wi-Fi network. Guest networks can isolate devices from one another. Tahany uses direct IPv4 connections and requires version 0.3.0 or later on both devices.

For USB installation from the project computer, run the following from this folder. Use the serial from `adb devices` and repeat for the other device.

```powershell
.\install.ps1 -Serial <device-serial>
```

This is the debug APK. See [TASKS.md](../TASKS.md) for real-device acceptance checks.
