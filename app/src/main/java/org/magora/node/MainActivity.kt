package org.magora.node

import android.Manifest
import android.app.Activity
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

        val startStop = Button(this).apply { text = "Start listening" }
        startStop.setOnClickListener {
            if (NodeState.listening(this@MainActivity)) {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                NodeState.setListening(this@MainActivity, false)
            } else {
                requestMicPermissionAndStart()
            }
        }

        val battery = Button(this).apply { text = "Allow while battery optimized" }
        battery.setOnClickListener { requestBatteryExemption() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(startStop)
            addView(battery)
        }
        setContentView(layout)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(this::refreshStatus, 1000)
    }

    private fun requestMicPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCapture()
    }

    private fun startCapture() {
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
        refreshStatus()
    }

    private fun requestBatteryExemption() {
        val packageName = packageName
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
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
            append("Battery temperature: ${if (temperature >= 0) String.format("%.1f °C", temperature / 10f) else "Unknown"}")
        }
        handler.postDelayed(this::refreshStatus, 1000)
    }
}
