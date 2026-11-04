package org.magora.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!NodeState.listening(context)) return
        ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java))
    }
}
