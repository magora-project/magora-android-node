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

    // --- Phase 2 ingest status (shown on the status screen; never holds secrets) ---

    /** Local-only switch: when off, nothing is enqueued or posted. On by default. */
    fun postingEnabled(context: Context): Boolean = prefs(context).getBoolean("posting_enabled", true)

    fun setPostingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("posting_enabled", enabled).apply()
    }

    fun setAuth(context: Context, ok: Boolean, status: Int) {
        prefs(context).edit()
            .putString("auth_state", if (ok) "ok" else "failed")
            .putInt("auth_status", status)
            .putLong("auth_at", System.currentTimeMillis())
            .apply()
    }

    fun authState(context: Context): String = prefs(context).getString("auth_state", "not yet") ?: "not yet"
    fun authStatus(context: Context): Int = prefs(context).getInt("auth_status", 0)
    fun authAt(context: Context): Long = prefs(context).getLong("auth_at", 0L)

    fun setLastPost(context: Context, millis: Long) {
        prefs(context).edit().putLong("last_post_at", millis).remove("backoff_reason").apply()
    }

    fun lastPostAt(context: Context): Long = prefs(context).getLong("last_post_at", 0L)

    fun setQueue(context: Context, depth: Int, parked: Int) {
        prefs(context).edit().putInt("queue_depth", depth).putInt("queue_parked", parked).apply()
    }

    fun queueDepth(context: Context): Int = prefs(context).getInt("queue_depth", 0)
    fun queueParked(context: Context): Int = prefs(context).getInt("queue_parked", 0)

    fun setBackoff(context: Context, reason: String, untilMillis: Long) {
        prefs(context).edit().putString("backoff_reason", reason).putLong("backoff_until", untilMillis).apply()
    }

    fun backoffReason(context: Context): String? = prefs(context).getString("backoff_reason", null)
    fun backoffUntil(context: Context): Long = prefs(context).getLong("backoff_until", 0L)

    fun setInferencePaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean("inference_paused", paused).apply()
    }

    fun inferencePaused(context: Context): Boolean = prefs(context).getBoolean("inference_paused", false)

    fun setFilterStatus(context: Context, whitelist: String, locationSpecies: Int, week48: Int) {
        prefs(context).edit()
            .putString("whitelist_status", whitelist)
            .putInt("location_species", locationSpecies)
            .putInt("location_week", week48)
            .apply()
    }

    fun whitelistStatus(context: Context): String = prefs(context).getString("whitelist_status", "pending") ?: "pending"
    fun locationSpecies(context: Context): Int = prefs(context).getInt("location_species", -1)
    fun locationWeek(context: Context): Int = prefs(context).getInt("location_week", 0)

    fun resetIngestStatus(context: Context) {
        prefs(context).edit()
            .remove("auth_state").remove("auth_status").remove("auth_at")
            .remove("last_post_at").remove("backoff_reason").remove("backoff_until")
            .apply()
    }
}
