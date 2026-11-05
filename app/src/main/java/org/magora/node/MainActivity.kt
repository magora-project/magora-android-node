package org.magora.node

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        // Phase 2: consume files/magora-config.json once (it is deleted afterwards).
        NodeConfig.importIfPresent(this)

        requestNotificationPermission()

        val startStop = Button(this).apply { text = "Start listening" }
        startStop.setOnClickListener {
            if (NodeState.listening(this@MainActivity)) {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                NodeState.setListening(this@MainActivity, false)
            } else {
                requestMicPermissionAndStart()
            }
        }

        val posting = Button(this)
        fun renderPosting() {
            posting.text = if (NodeState.postingEnabled(this)) "Posting to Magora: ON (tap to stop)" else "Posting to Magora: OFF (tap to resume)"
        }
        renderPosting()
        posting.setOnClickListener {
            val enabled = !NodeState.postingEnabled(this)
            NodeState.setPostingEnabled(this, enabled)
            DailyLog.append(this, "event=posting_toggled enabled=$enabled")
            renderPosting()
            refreshStatus()
        }

        val battery = Button(this).apply { text = "Allow while battery optimized" }
        battery.setOnClickListener { requestBatteryExemption() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(startStop)
            addView(posting)
            addView(battery)
        }
        setContentView(layout)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(NotificationManager::class.java).cancel(BootReceiver.RESUME_NOTIFICATION_ID)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val reason = if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) "home" else "manual"
            when {
                NodeState.deferred(this) || NodeState.lastCycleSilent(this) -> startCapture(reason, restartExisting = true)
                else -> startCapture(reason)
            }
        }
        handler.postDelayed(this::refreshStatus, 1000)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }

    private fun requestMicPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture("manual")
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCapture("manual")
    }

    private fun startCapture(reason: String, restartExisting: Boolean = false) {
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_START_REASON, reason)
            .putExtra(CaptureService.EXTRA_RESTART_CAPTURE, restartExisting)
        ContextCompat.startForegroundService(this, intent)
        refreshStatus()
    }

    private fun requestBatteryExemption() {
        val packageName = packageName
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun ingestStatus(): String {
        val config = NodeConfig.load(this) ?: return "\nMagora ingest: no node config imported (local only)"
        val ctx = this
        return buildString {
            append("\nNode: ${config.nodeName}\n")
            append("Posting: ${if (NodeState.postingEnabled(ctx)) "ON" else "OFF (local toggle)"}\n")
            val authAt = NodeState.authAt(ctx)
            append(
                when (NodeState.authState(ctx)) {
                    "ok" -> "Auth: OK (${since(authAt)})\n"
                    "failed" -> "Auth: FAILED, HTTP ${NodeState.authStatus(ctx)} (${since(authAt)})\n"
                    else -> "Auth: not yet\n"
                }
            )
            val lastPost = NodeState.lastPostAt(ctx)
            append("Last successful post: ${if (lastPost == 0L) "never" else "${Rows.timestamp(java.time.Instant.ofEpochMilli(lastPost))} (${since(lastPost)})"}\n")
            append("Queue depth: ${NodeState.queueDepth(ctx)} (parked: ${NodeState.queueParked(ctx)})\n")
            NodeState.backoffReason(ctx)?.let { reason ->
                val until = NodeState.backoffUntil(ctx)
                if (until > System.currentTimeMillis()) append("Retrying after: $reason, in ${(until - System.currentTimeMillis()) / 1000}s\n")
            }
            append("Whitelist: ${NodeState.whitelistStatus(ctx)}\n")
            val species = NodeState.locationSpecies(ctx)
            append("Location filter: ${if (species < 0) "not active" else "$species species, week ${NodeState.locationWeek(ctx)}/48"}")
        }
    }

    private fun since(millis: Long): String {
        if (millis == 0L) return "never"
        val seconds = (System.currentTimeMillis() - millis) / 1000
        return when {
            seconds < 120 -> "${seconds}s ago"
            seconds < 7200 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    private fun refreshStatus() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val uptimeMillis = if (NodeState.listening(this)) System.currentTimeMillis() - NodeState.startedAt(this) else 0L
        val uptime = "${uptimeMillis / 3_600_000}h ${(uptimeMillis / 60_000) % 60}m"
        status.text = buildString {
            append("Listening: ${if (NodeState.listening(this@MainActivity)) "YES" else "NO"}\n")
            append("Input device: ${NodeState.inputDevice(this@MainActivity)}\n")
            append("Granted source: ${NodeState.source(this@MainActivity)}\n")
            append("Cycles completed: ${NodeState.cycles(this@MainActivity)}\n")
            append("Uptime: $uptime\n")
            append("Battery temperature: ${if (temperature >= 0) String.format("%.1f °C", temperature / 10f) else "Unknown"}\n")
            append("BirdNET inference: ${if (NodeState.inferencePaused(this@MainActivity)) "PAUSED (battery >= 45 °C; resumes below 40 °C)" else "running"}\n")
            append(ingestStatus())
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(this::refreshStatus, 1000)
    }
}
