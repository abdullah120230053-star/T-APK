package com.dama.tahany.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dama.tahany.data.SettingsRepository
import com.dama.tahany.protocol.TahanyMessage
import com.dama.tahany.service.TahanyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs on the Phone (Redmi). Captures notifications, extracts title/text and any
 * replyable RemoteInput action, and forwards them over the Tahany link.
 *
 * Replies received from the tablet are dispatched back to the original notification's
 * PendingIntent.
 */
class TahanyNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        activeInstance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) activeInstance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Never mirror notifications from ourselves or system download managers
        if (pkg == packageName) return

        val notification = sbn.notification ?: return
        val flags = notification.flags
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0

        // Skip persistent ongoing notifications unless they have actions (e.g. media player)
        val actions = notification.actions
        if (isOngoing && (actions == null || actions.isEmpty())) return

        scope.launch {
            if (settings.isPackageBlocked(pkg)) return@launch

            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()

            if (title.isBlank() && text.isBlank()) return@launch

            val appLabel = try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }

            // Find reply action with RemoteInput if present
            var replyAction: Notification.Action? = null
            if (actions != null) {
                for (action in actions) {
                    if (!action.remoteInputs.isNullOrEmpty()) {
                        replyAction = action
                        break
                    }
                }
            }

            val key = sbn.key
            if (replyAction != null) {
                replyActions[key] = CachedAction(replyAction, sbn.postTime)
            }

            val event = TahanyMessage.NotificationEvent(
                key = key,
                packageName = pkg,
                appLabel = appLabel,
                title = title,
                text = text,
                postedAt = sbn.postTime,
                hasReply = replyAction != null,
                canOpen = notification.contentIntent != null,
            )

            TahanyService.forwardNotification(event)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) {
            replyActions.remove(sbn.key)
        }
    }

    companion object {
        private const val TAG = "TahanyNotification"
        private var activeInstance: TahanyNotificationListenerService? = null

        private data class CachedAction(
            val action: Notification.Action,
            val timestamp: Long,
        )

        private val replyActions = ConcurrentHashMap<String, CachedAction>()

        fun isConnected(): Boolean = activeInstance != null

        fun dispatchReply(context: Context, key: String, replyText: String): Boolean {
            val cached = replyActions[key] ?: return false
            val action = cached.action
            val remoteInputs = action.remoteInputs ?: return false
            if (remoteInputs.isEmpty()) return false

            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

            return try {
                action.actionIntent.send(context, 0, intent)
                Log.d(TAG, "Dispatched remote reply for notification key: $key")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply for key $key", e)
                false
            }
        }
    }
}
