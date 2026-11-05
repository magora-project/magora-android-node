package org.magora.node

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale

object BatteryInfo {

    data class Reading(val percent: Int, val temperatureC: Double, val state: String)

    fun read(context: Context): Reading {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val temperatureC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000) ?: -1000) / 10.0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val state = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> "full"
            status == BatteryManager.BATTERY_STATUS_CHARGING -> when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "charging_ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "charging_usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging_wireless"
                else -> "charging"
            }
            status == BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            status == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        return Reading(percent, temperatureC, state)
    }

    fun formatTemp(temperatureC: Double): String = String.format(Locale.US, "%.1f", temperatureC)
}
