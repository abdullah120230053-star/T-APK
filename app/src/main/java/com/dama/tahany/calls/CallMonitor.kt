@file:Suppress("DEPRECATION")

package com.dama.tahany.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dama.tahany.protocol.CallState
import com.dama.tahany.protocol.TahanyMessage

/**
 * Listens for cellular call state changes on the Phone (Redmi).
 * Emits CallEvent messages over the link to the Honor Pad tablet.
 */
class CallMonitor(
    private val context: Context,
    private val onCallEvent: (TahanyMessage.CallEvent) -> Unit,
) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var callback: Any? = null
    private var isRegistered = false
    private var withCallerId = false

    fun start() {
        if (telephonyManager == null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stop()
            return
        }

        val callerId = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        if (isRegistered && callerId == withCallerId) return
        stop()
        withCallerId = callerId

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !callerId) {
                val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleState(state, null)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
                callback = telephonyCallback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                callback = listener
            }
            isRegistered = true
            Log.d(TAG, "Call monitor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call monitor", e)
        }
    }

    fun stop() {
        if (!isRegistered || telephonyManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !withCallerId) {
                (callback as? TelephonyCallback)?.let { telephonyManager.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                (callback as? PhoneStateListener)?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
            callback = null
            isRegistered = false
            Log.d(TAG, "Call monitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop call monitor", e)
        }
    }

    private fun handleState(state: Int, phoneNumber: String?) {
        val callState = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
            else -> return
        }

        val number = phoneNumber?.takeIf { it.isNotBlank() }
        val contactName = if (callState == CallState.RINGING && number != null) {
            lookupContactName(number)
        } else null

        val event = TahanyMessage.CallEvent(
            state = callState,
            number = number,
            contactName = contactName,
            timestamp = System.currentTimeMillis(),
        )
        onCallEvent(event)
    }

    private fun lookupContactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "TahanyCallMonitor"
    }
}
