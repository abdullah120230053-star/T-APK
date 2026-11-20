package com.dama.tahany.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class GuideStep(
    val title: String,
    val body: String,
    val intent: Intent? = null,
)

/**
 * Xiaomi/HyperOS and Honor/MagicOS both kill background work aggressively.
 * There is no public API that flips Autostart on, so the honest thing is to
 * open the vendor screen when it exists and fall back to app settings.
 */
object OemGuide {
    fun steps(context: Context): List<GuideStep> {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val appDetails = appDetailsIntent(context)
        val steps = mutableListOf<GuideStep>()

        when {
            manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> {
                steps += GuideStep(
                    title = "Turn on Autostart",
                    body = "Security → Permissions → Autostart → Tahany → On. MIUI and HyperOS stop " +
                        "third-party links otherwise, usually within a few minutes.",
                    intent = resolve(context, xiaomiAutostartIntent()) ?: appDetails,
                )
                steps += GuideStep(
                    title = "Battery: No restrictions",
                    body = "Settings → Apps → Tahany → Battery saver → No restrictions. " +
                        "Also lock Tahany in Recents so the system does not clear it.",
                    intent = resolve(context, xiaomiBatteryIntent()) ?: appDetails,
                )
            }

            manufacturer.contains("honor") || manufacturer.contains("huawei") -> {
                steps += GuideStep(
                    title = "Allow background activity",
                    body = "Settings → Battery → App launch → Tahany → Manage manually, with " +
                        "Auto-launch, Secondary launch, and Run in background enabled.",
                    intent = resolve(context, honorStartupIntent()) ?: appDetails,
                )
                steps += GuideStep(
                    title = "Keep the link alive",
                    body = "Settings → Battery → More battery settings → turn off Power-saving mode " +
                        "or exclude Tahany. The Honor battery manager can stop the service silently.",
                    intent = appDetails,
                )
            }

            else -> {
                steps += GuideStep(
                    title = "Allow background activity",
                    body = "Settings → Apps → Tahany → Battery → Unrestricted. Then lock Tahany " +
                        "in Recents if your build clears background apps.",
                    intent = appDetails,
                )
            }
        }

        steps += GuideStep(
            title = "Check battery optimisation",
            body = if (isIgnoringBatteryOptimizations(context)) {
                "Tahany is already exempt from battery optimisation."
            } else {
                "If your build still stops the service, exempt Tahany from battery optimisation."
            },
            intent = batteryOptimizationIntent(context),
        )
        return steps
    }

    fun open(context: Context, step: GuideStep) {
        val intent = step.intent ?: appDetailsIntent(context)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun appDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    private fun resolve(context: Context, intent: Intent?): Intent? {
        if (intent == null) return null
        return if (context.packageManager.resolveActivity(intent, 0) != null) intent else null
    }

    private fun xiaomiAutostartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    }

    private fun xiaomiBatteryIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        )
    }

    private fun honorStartupIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
        )
    }
}
