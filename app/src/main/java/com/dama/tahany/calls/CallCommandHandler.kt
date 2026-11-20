package com.dama.tahany.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dama.tahany.protocol.CallAction

/**
 * Executes remote call control commands on the Phone (Redmi) sent by the Tablet.
 */
class CallCommandHandler(private val context: Context) {
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun execute(action: CallAction): Boolean {
        Log.d(TAG, "Executing call command: $action")
        return when (action) {
            CallAction.ANSWER -> answerCall()
            CallAction.DECLINE -> declineCall()
            CallAction.SPEAKER_ON -> setSpeaker(true)
            CallAction.SPEAKER_OFF -> setSpeaker(false)
        }
    }

    private fun answerCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted")
                return false
            }
            return try {
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept call", e)
                false
            }
        }
        return false
    }

    private fun declineCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted")
                return false
            }
            return try {
                @Suppress("DEPRECATION")
                telecomManager?.endCall() ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end call", e)
                false
            }
        }
        return false
    }

    private fun setSpeaker(on: Boolean): Boolean {
        return try {
            audioManager?.isSpeakerphoneOn = on
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle speakerphone", e)
            false
        }
    }

    companion object {
        private const val TAG = "CallCommandHandler"
    }
}
