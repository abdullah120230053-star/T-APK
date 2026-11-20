package com.dama.tahany.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Re-arms the foreground service after a reboot. The service itself decides
 * whether a role has been chosen and whether the user granted the required
 * runtime permissions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val launch = Intent(context, TahanyService::class.java)
            .setAction(TahanyService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, launch)
        } catch (_: Exception) {
            // Some OEM builds and Android versions refuse a background
            // foreground-service start from BOOT_COMPLETED. The user can still
            // open Tahany, which starts the service in the foreground.
        }
    }
}
