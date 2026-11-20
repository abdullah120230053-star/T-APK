package com.dama.tahany.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dama.tahany.protocol.CallAction
import com.dama.tahany.service.TahanyService

/**
 * Handles incoming call actions (Answer, Decline, Speakerphone) clicked from the Tablet notification.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val actionName = intent.getStringExtra(EXTRA_ACTION) ?: return
        val action = runCatching { CallAction.valueOf(actionName) }.getOrNull() ?: return

        val serviceIntent = Intent(context, TahanyService::class.java).apply {
            this.action = TahanyService.ACTION_SEND_CALL_COMMAND
            putExtra(TahanyService.EXTRA_CALL_ACTION, action.name)
        }
        context.startService(serviceIntent)
    }

    companion object {
        const val EXTRA_ACTION = "com.dama.tahany.CALL_ACTION"
    }
}
