package org.magora.node

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!NodeState.listening(context)) return
        // Start foreground in deferred mode (no microphone while backgrounded); the
        // home activity takes over when it shows. The notification is the fallback.
        val serviceIntent = Intent(context, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_START_REASON, "boot")
            .putExtra(CaptureService.EXTRA_DEFER_CAPTURE, true)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
        }
        postResumeNotification(context)
    }

    companion object {
        const val RESUME_NOTIFICATION_ID = 2

        @JvmStatic
        fun postResumeNotification(context: Context) {
            val channelId = "magora_node_resume"
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Magora listening", NotificationManager.IMPORTANCE_HIGH)
            )
            val launchIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                RESUME_NOTIFICATION_ID,
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_node)
                    .setContentTitle("Tap to resume listening")
                    .setContentText("Magora needs foreground access to start the microphone")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
