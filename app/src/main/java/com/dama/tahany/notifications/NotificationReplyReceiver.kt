package com.dama.tahany.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.dama.tahany.service.TahanyService

/**
 * Handles inline replies submitted from the Tablet's mirrored notification.
 * Dispatches the text over the link to the Phone.
 */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim() ?: return

        if (text.isNotEmpty()) {
            val serviceIntent = Intent(context, TahanyService::class.java).apply {
                action = TahanyService.ACTION_SEND_NOTIFICATION_REPLY
                putExtra(TahanyService.EXTRA_NOTIFICATION_KEY, key)
                putExtra(TahanyService.EXTRA_REPLY_TEXT, text)
            }
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_KEY = "com.dama.tahany.NOTIFICATION_KEY"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}
