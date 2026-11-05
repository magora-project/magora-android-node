package org.magora.node

import android.content.Context
import android.content.SharedPreferences

object NodeState {
    private const val PREFS = "magora_node_state"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setListening(context: Context, listening: Boolean) {
        prefs(context).edit().putBoolean("listening", listening).apply()
    }

    fun setStartedAt(context: Context, millis: Long) {
        prefs(context).edit().putLong("started_at", millis).apply()
    }

    fun incrementCycles(context: Context) {
        prefs(context).edit().putInt("cycles", cycles(context) + 1).apply()
    }

    fun cycles(context: Context): Int = prefs(context).getInt("cycles", 0)

    fun setCycleFields(context: Context, source: String, inputDevice: String) {
        prefs(context).edit()
            .putString("source", source)
            .putString("input_device", inputDevice)
            .apply()
    }

    fun listening(context: Context): Boolean = prefs(context).getBoolean("listening", false)
    fun startedAt(context: Context): Long = prefs(context).getLong("started_at", 0L)
    fun source(context: Context): String = prefs(context).getString("source", "Unknown") ?: "Unknown"
    fun inputDevice(context: Context): String = prefs(context).getString("input_device", "Unknown") ?: "Unknown"

    fun setDeferred(context: Context, deferred: Boolean) {
        prefs(context).edit().putBoolean("deferred", deferred).apply()
    }

    fun deferred(context: Context): Boolean = prefs(context).getBoolean("deferred", false)

    fun setLastCycleSilent(context: Context, silent: Boolean) {
        prefs(context).edit().putBoolean("last_cycle_silent", silent).apply()
    }

    fun lastCycleSilent(context: Context): Boolean = prefs(context).getBoolean("last_cycle_silent", false)

    fun setStartReason(context: Context, reason: String) {
        prefs(context).edit().putString("start_reason", reason).apply()
    }

    fun startReason(context: Context): String = prefs(context).getString("start_reason", "unknown") ?: "unknown"
}
