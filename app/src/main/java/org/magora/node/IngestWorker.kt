package org.magora.node

import android.content.Context
import java.io.IOException

/**
 * Drains [IngestQueue] to Supabase in order, one row at a time, as the node's own user.
 *
 * - 2xx: delivered; the row is acknowledged.
 * - 401: sign in again and retry the row once; if that is also refused, back off (auth).
 * - Transport errors, 5xx, 408 and 429: retry the same row with exponential backoff.
 * - Any other 4xx: logged and parked (moved to parked.log), never retried forever.
 * - Rows older than 7 days are dropped by the queue and logged.
 *
 * Nothing here logs a token, a credential or a key.
 */
class IngestWorker(
    private val context: Context,
    private val queue: IngestQueue,
    private val client: SupabaseClient,
) {
    private val lock = Object()
    @Volatile private var alive = true
    private var backoffMillis = 0L
    private var thread: Thread? = null

    fun start() {
        val t = Thread({
            // Exactly one drainer per queue: the queue's drain lock is held for the whole loop.
            // A stopped worker still finishing an HTTP request keeps it until that request
            // ends; a waiter that is stopped gives up without ever taking it.
            try {
                queue.drainLock.lockInterruptibly()
            } catch (_: InterruptedException) {
                return@Thread
            }
            try {
                if (alive) run()
            } finally {
                queue.drainLock.unlock()
            }
        }, "magora-ingest")
        thread = t
        t.start()
    }

    fun stop() {
        alive = false
        synchronized(lock) { lock.notifyAll() }
        thread?.interrupt()
    }

    /** Called after a row is enqueued, to wake the drain loop. */
    fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun run() {
        publishDepth()
        while (alive) {
            try {
                if (!NodeState.postingEnabled(context)) {
                    sleep(IDLE_MILLIS)
                    continue
                }
                val entry = queue.peek { expired ->
                    DailyLog.append(context, "event=queue_expired seq=${expired.seq} table=${expired.table}")
                }
                publishDepth()
                if (entry == null) {
                    sleep(IDLE_MILLIS)
                    continue
                }
                deliver(entry)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                DailyLog.append(context, "event=ingest_error error=${error.javaClass.simpleName}")
                backOff("ingest_error")
            }
        }
    }

    private fun postingAllowed(): Boolean = alive && NodeState.postingEnabled(context)

    private fun deliver(entry: IngestQueue.Entry) {
        if (!postingAllowed()) return
        try {
            client.ensureToken()
            NodeState.setAuth(context, ok = true, status = 200)
        } catch (error: SupabaseClient.AuthException) {
            NodeState.setAuth(context, ok = false, status = error.status)
            DailyLog.append(context, "event=auth_failed status=${error.status}")
            backOff("auth")
            return
        } catch (_: IOException) {
            backOff("transport")
            return
        }

        // The posting switch is re-checked before every HTTP attempt, so OFF stops any request
        // that has not started yet (one already in flight is allowed to finish).
        if (!postingAllowed()) return
        var status = try {
            client.postRow(entry.table, entry.json)
        } catch (_: IOException) {
            backOff("transport")
            return
        }
        if (status == 401) {
            DailyLog.append(context, "event=post_unauthorized seq=${entry.seq} action=reauth")
            try {
                if (!postingAllowed()) return
                client.signIn()
                NodeState.setAuth(context, ok = true, status = 200)
                if (!postingAllowed()) return
                status = client.postRow(entry.table, entry.json)
            } catch (error: SupabaseClient.AuthException) {
                NodeState.setAuth(context, ok = false, status = error.status)
                DailyLog.append(context, "event=auth_failed status=${error.status}")
                backOff("auth")
                return
            } catch (_: IOException) {
                backOff("transport")
                return
            }
            if (status == 401) {
                client.invalidateToken()
                NodeState.setAuth(context, ok = false, status = 401)
                DailyLog.append(context, "event=auth_rejected seq=${entry.seq} status=401")
                backOff("auth")
                return
            }
        }
        when {
            status in 200..299 -> {
                queue.ack(entry.seq)
                backoffMillis = 0L
                NodeState.setLastPost(context, System.currentTimeMillis())
                DailyLog.append(context, "event=post_ok seq=${entry.seq} table=${entry.table} status=$status")
            }
            status >= 500 || status == 408 || status == 429 -> {
                DailyLog.append(context, "event=post_retry seq=${entry.seq} table=${entry.table} status=$status")
                backOff("http_$status")
            }
            else -> {
                queue.park(entry.seq, status)
                DailyLog.append(context, "event=post_parked seq=${entry.seq} table=${entry.table} status=$status")
            }
        }
        publishDepth()
    }

    private fun backOff(reason: String) {
        backoffMillis = if (backoffMillis == 0L) MIN_BACKOFF_MILLIS else minOf(backoffMillis * 2, MAX_BACKOFF_MILLIS)
        NodeState.setBackoff(context, reason, System.currentTimeMillis() + backoffMillis)
        // Not woken by new rows: a backoff runs its full length (stop() interrupts it).
        Thread.sleep(backoffMillis)
    }

    private fun publishDepth() {
        NodeState.setQueue(context, queue.depth(), queue.parkedCount())
    }

    private fun sleep(millis: Long) {
        synchronized(lock) {
            if (alive) lock.wait(millis)
        }
    }

    companion object {
        const val IDLE_MILLIS = 5_000L
        const val MIN_BACKOFF_MILLIS = 5_000L
        const val MAX_BACKOFF_MILLIS = 5L * 60 * 1000
    }
}
