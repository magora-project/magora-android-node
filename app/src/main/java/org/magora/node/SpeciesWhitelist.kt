package org.magora.node

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The node's portal-set eBird species whitelist, resolved once per service start exactly as
 * detect.py fetch_whitelist() does (docs/node-contract.md § Species whitelist):
 * up to six fetch attempts 10 s apart; a non-empty server array wins and is cached; a
 * null/empty server value stops retrying and falls back to the cache; with no usable server
 * value or cache, detections run without species filtering.
 */
class SpeciesWhitelist(private val context: Context, nodeId: String) {
    /** Cached per node, so a re-provisioned phone never filters with another node's list. */
    private val cacheFile = File(context.filesDir, "species_whitelist-$nodeId.json")
    private val resolved = CountDownLatch(1)

    @Volatile
    var species: Set<String>? = null
        private set

    @Volatile
    var source: String = "pending"
        private set

    /** Blocking; run on a background thread. */
    fun resolve(client: SupabaseClient, shouldContinue: () -> Boolean) {
        try {
            for (attempt in 1..6) {
                if (!shouldContinue()) break
                try {
                    val wl = client.fetchWhitelist()
                    if (!wl.isNullOrEmpty()) {
                        cacheFile.writeText(JSONArray(wl).toString(), Charsets.UTF_8)
                        species = wl.map { it.lowercase() }.toSet()
                        source = "server"
                        DailyLog.append(context, "event=whitelist_loaded source=server species=${wl.size}")
                        return
                    }
                    DailyLog.append(context, "event=whitelist_unset_on_server")
                    break
                } catch (error: Exception) {
                    DailyLog.append(context, "event=whitelist_fetch_error attempt=$attempt/6 error=${error.javaClass.simpleName}")
                    if (attempt < 6) Thread.sleep(10_000)
                }
            }
            val cached = readCache()
            if (!cached.isNullOrEmpty()) {
                species = cached.map { it.lowercase() }.toSet()
                source = "cache"
                DailyLog.append(context, "event=whitelist_loaded source=cache species=${cached.size}")
            } else {
                species = null
                source = "none"
                DailyLog.append(context, "event=whitelist_none filtering=off")
            }
        } finally {
            resolved.countDown()
        }
    }

    /** Waits for [resolve] to finish, at most [timeoutMillis]; returns false on timeout. */
    fun await(timeoutMillis: Long): Boolean = resolved.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun readCache(): List<String>? = try {
        if (!cacheFile.exists()) null else JSONArray(cacheFile.readText(Charsets.UTF_8)).let { a -> List(a.length()) { a.getString(it) } }
    } catch (_: Exception) {
        null
    }
}
