package com.dama.tahany.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dama.tahany.model.DeviceRole

data class PermissionItem(
    val permission: String,
    val title: String,
    val why: String,
)

/**
 * Runtime permissions only. Normal permissions (foreground service, boot
 * receiver, internet) are declared in the manifest and are not requestable.
 *
 * The Android-version branches are deliberate: asking for READ_MEDIA_IMAGES on
 * Android 12, or for location on Android 13+, is the usual cause of a
 * permission dialog that never appears.
 */
object PermissionCatalog {
    fun items(role: DeviceRole? = null, api: Int = Build.VERSION.SDK_INT): List<PermissionItem> = buildList {
        if (api >= 31) {
            add(
                PermissionItem(
                    Manifest.permission.BLUETOOTH_SCAN,
                    "Nearby devices",
                    "Find the other device over Bluetooth.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    "Be discoverable",
                    "Let the other device find this one.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    "Bluetooth connection",
                    "Open the encrypted link between the two devices.",
                ),
            )
        }
        if (api >= 33) {
            add(
                PermissionItem(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    "Nearby Wi-Fi",
                    "Use direct Wi-Fi for faster file and screen transfer.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.POST_NOTIFICATIONS,
                    "Notifications",
                    "Show the link status and transfer progress.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    "Photos",
                    "Detect new screenshots and save the ones you receive.",
                ),
            )
        } else {
            add(
                PermissionItem(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    "Location",
                    "Android 12 and below require location for nearby discovery.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    "Storage",
                    "Detect new screenshots and save received images.",
                ),
            )
            if (api <= 28) {
                add(
                    PermissionItem(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        "Storage",
                        "Save received screenshots on this Android version.",
                    ),
                )
            }
        }

        if (role == DeviceRole.PHONE) {
            add(
                PermissionItem(
                    Manifest.permission.READ_PHONE_STATE,
                    "Phone calls",
                    "Alert the tablet when a call rings on your phone.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.READ_CALL_LOG,
                    "Caller ID",
                    "Identify incoming phone numbers for the tablet.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.READ_CONTACTS,
                    "Contacts",
                    "Display the caller's name on the tablet.",
                ),
            )
            add(
                PermissionItem(
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    "Call control",
                    "Answer and decline phone calls from the tablet.",
                ),
            )
        }
    }

    fun missing(context: Context, role: DeviceRole? = null, api: Int = Build.VERSION.SDK_INT): List<PermissionItem> =
        items(role, api).filterNot { context.has(it.permission) }

    fun hasAll(context: Context, role: DeviceRole? = null, api: Int = Build.VERSION.SDK_INT): Boolean =
        missing(context, role, api).isEmpty()

    fun roleHint(role: DeviceRole?): String = when (role) {
        DeviceRole.PHONE -> "Phone mode: the phone keeps the link, notifications, and calls."
        DeviceRole.TABLET -> "Tablet mode: the tablet shows files, screenshots, and the phone screen."
        null -> "Choose a role first."
    }
}

private fun Context.has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
