package com.dama.tahany.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.dama.tahany.MainActivity
import com.dama.tahany.R
import com.dama.tahany.calls.CallActionReceiver
import com.dama.tahany.files.SavedFile
import com.dama.tahany.notifications.NotificationReplyReceiver
import com.dama.tahany.protocol.CallAction
import com.dama.tahany.protocol.TahanyMessage

object NotificationHelper {
    const val CHANNEL_SERVICE = "tahany_service"
    const val CHANNEL_TRANSFERS = "tahany_transfers"
    const val CHANNEL_INCOMING = "tahany_incoming"
    const val CHANNEL_MIRRORED = "tahany_mirrored"
    const val CHANNEL_CALLS = "tahany_calls"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val CALL_NOTIFICATION_ID = 2001
    private const val INCOMING_ID_BASE = 3000
    private const val MIRRORED_ID_BASE = 4000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the encrypted Tahany link alive."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSFERS,
                context.getString(R.string.notification_channel_transfers),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "File and screenshot transfer progress."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                context.getString(R.string.notification_channel_incoming),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A file or screenshot arrived from the other device."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MIRRORED,
                context.getString(R.string.notification_channel_mirrored),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications forwarded from your phone."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                context.getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming call alerts from your phone."
                enableVibration(true)
            },
        )
    }

    fun serviceNotification(context: Context, text: String, connected: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        val stopIntent = PendingIntent.getService(
            context,
            11,
            Intent(context, TahanyService::class.java).setAction(TahanyService.ACTION_STOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_tahany)
            .setContentTitle(context.getString(R.string.notification_service_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_stop), stopIntent)
            .setShowWhen(!connected)
            .build()
    }

    fun notifyService(context: Context, text: String, connected: Boolean) {
        notify(
            context,
            SERVICE_NOTIFICATION_ID,
            serviceNotification(context, text, connected),
        )
    }

    fun transferNotification(
        context: Context,
        id: Int,
        title: String,
        text: String,
        progress: Int? = null,
    ): Notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
        .setSmallIcon(R.drawable.ic_stat_tahany)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(progress != null)
        .setProgress(100, progress ?: 0, progress == null)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun incomingFileNotification(
        context: Context,
        saved: SavedFile,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            20 + (saved.displayName.hashCode() and 0xff),
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(saved.uri, saved.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            pendingFlags(),
        )
        val copyIntent = PendingIntent.getService(
            context,
            30 + (saved.displayName.hashCode() and 0xff),
            Intent(context, TahanyService::class.java)
                .setAction(TahanyService.ACTION_COPY_TEXT)
                .putExtra(TahanyService.EXTRA_TEXT, saved.uri.toString()),
            pendingFlags(),
        )
        return NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_tahany)
            .setContentTitle("Received ${saved.displayName}")
            .setContentText("Saved to ${if (saved.isImage) "Pictures" else "Downloads"}/Tahany")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Saved to ${if (saved.isImage) "Pictures" else "Downloads"}/Tahany",
                ),
            )
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(0, context.getString(R.string.notification_copy), copyIntent)
            .build()
    }

    fun notifyIncoming(context: Context, saved: SavedFile) {
        notify(
            context,
            INCOMING_ID_BASE + (saved.displayName.hashCode() and 0x0fff),
            incomingFileNotification(context, saved),
        )
    }

    fun notifyMirrored(context: Context, event: TahanyMessage.NotificationEvent) {
        val contentIntent = PendingIntent.getActivity(
            context,
            50 + (event.key.hashCode() and 0xff),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MIRRORED)
            .setSmallIcon(R.drawable.ic_stat_tahany)
            .setContentTitle("${event.title.ifBlank { event.appLabel }} (${event.appLabel})")
            .setContentText(event.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (event.hasReply) {
            val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.notification_reply))
                .build()

            val replyIntent = PendingIntent.getBroadcast(
                context,
                60 + (event.key.hashCode() and 0xff),
                Intent(context, NotificationReplyReceiver::class.java)
                    .putExtra(NotificationReplyReceiver.EXTRA_KEY, event.key),
                pendingFlags(),
            )

            val replyAction = NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.notification_reply),
                replyIntent,
            ).addRemoteInput(remoteInput).build()

            builder.addAction(replyAction)
        }

        val id = MIRRORED_ID_BASE + (event.key.hashCode() and 0x0fff)
        notify(context, id, builder.build())
    }

    fun notifyIncomingCall(
        context: Context,
        event: TahanyMessage.CallEvent,
        isSpeakerOn: Boolean = false,
    ) {
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )

        val declineIntent = PendingIntent.getBroadcast(
            context,
            101,
            Intent(context, CallActionReceiver::class.java)
                .putExtra(CallActionReceiver.EXTRA_ACTION, CallAction.DECLINE.name),
            pendingFlags(),
        )

        val answerIntent = PendingIntent.getBroadcast(
            context,
            102,
            Intent(context, CallActionReceiver::class.java)
                .putExtra(CallActionReceiver.EXTRA_ACTION, CallAction.ANSWER.name),
            pendingFlags(),
        )

        val nextSpeakerAction = if (isSpeakerOn) CallAction.SPEAKER_OFF else CallAction.SPEAKER_ON
        val speakerIntent = PendingIntent.getBroadcast(
            context,
            103,
            Intent(context, CallActionReceiver::class.java)
                .putExtra(CallActionReceiver.EXTRA_ACTION, nextSpeakerAction.name),
            pendingFlags(),
        )

        val callerDisplay = event.contactName ?: event.number ?: "Incoming phone call"

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_tahany)
            .setContentTitle("Call on phone: $callerDisplay")
            .setContentText("Voice audio stays on your phone • Answer or decline")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, context.getString(R.string.notification_decline), declineIntent)
            .addAction(0, context.getString(R.string.notification_answer), answerIntent)
            .addAction(
                0,
                if (isSpeakerOn) "Speaker Off" else context.getString(R.string.notification_speaker),
                speakerIntent,
            )
            .build()

        notify(context, CALL_NOTIFICATION_ID, notification)
    }

    fun cancelCall(context: Context) {
        cancel(context, CALL_NOTIFICATION_ID)
    }

    fun notify(context: Context, id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS can be revoked while the link is running.
        }
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private const val INCOMING_ID_BASE = 3000
}
