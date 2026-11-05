package org.magora.node

import java.io.File
import java.io.FileOutputStream

/**
 * Persistent, strictly ordered outbound queue for Supabase rows (detections and aci_logs
 * share one queue, so delivery order is capture order across both tables).
 *
 * Layout in [dir]:
 * - queue.log: append-only, one entry per line: `seq<TAB>table<TAB>enqueuedAtMillis<TAB>json`.
 * - head: the highest seq that has been delivered, parked or expired. Entries at or below it
 *   are ignored on load and removed at the next compaction.
 * - parked.log: rows the server rejected with a non-retryable 4xx, kept for inspection.
 *
 * Delivery is at-least-once: a crash between a successful POST and the head write re-sends
 * that one row after restart.
 */
class IngestQueue internal constructor(
    private val dir: File,
    private val maxAgeMillis: Long = SEVEN_DAYS_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val seq: Long, val table: String, val enqueuedAt: Long, val json: String)

    private val queueFile = File(dir, "queue.log")
    private val headFile = File(dir, "head")
    private val parkedFile = File(dir, "parked.log")
    private val entries = ArrayDeque<Entry>()
    private var head = 0L
    private var nextSeq = 1L
    private var doneSinceCompaction = 0
    private var parked = 0

    init {
        dir.mkdirs()
        load()
    }

    @Synchronized
    fun enqueue(table: String, json: String): Entry {
        require(table in TABLES) { "unknown table" }
        require('\n' !in json && '\r' !in json) { "row JSON must be one line" }
        val entry = Entry(nextSeq++, table, clock(), json)
        FileOutputStream(queueFile, true).use { out ->
            out.write("${entry.seq}\t${entry.table}\t${entry.enqueuedAt}\t${entry.json}\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        entries.addLast(entry)
        return entry
    }

    /** The oldest undelivered row, after dropping rows past the age bound (reported via [onExpired]). */
    @Synchronized
    fun peek(onExpired: (Entry) -> Unit = {}): Entry? {
        val cutoff = clock() - maxAgeMillis
        var expired = false
        while (entries.isNotEmpty() && entries.first().enqueuedAt < cutoff) {
            val dropped = entries.removeFirst()
            head = dropped.seq
            doneSinceCompaction++
            expired = true
            onExpired(dropped)
        }
        if (expired) {
            persistHead()
            maybeCompact()
        }
        return entries.firstOrNull()
    }

    /** Marks the head row delivered. */
    @Synchronized
    fun ack(seq: Long) {
        val first = entries.firstOrNull() ?: return
        require(first.seq == seq) { "ack out of order" }
        entries.removeFirst()
        head = seq
        doneSinceCompaction++
        persistHead()
        maybeCompact()
    }

    /** Moves the head row to parked.log (non-retryable rejection) and advances past it. */
    @Synchronized
    fun park(seq: Long, status: Int) {
        val first = entries.firstOrNull() ?: return
        require(first.seq == seq) { "park out of order" }
        FileOutputStream(parkedFile, true).use { out ->
            out.write("${first.seq}\t${first.table}\t${first.enqueuedAt}\t$status\t${first.json}\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        parked++
        ack(seq)
    }

    @Synchronized
    fun depth(): Int = entries.size

    @Synchronized
    fun parkedCount(): Int = parked

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    private fun load() {
        head = headFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        var maxSeq = head
        if (queueFile.exists()) {
            queueFile.forEachLine(Charsets.UTF_8) { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size != 4) return@forEachLine // torn final write
                val seq = parts[0].toLongOrNull() ?: return@forEachLine
                val enqueuedAt = parts[2].toLongOrNull() ?: return@forEachLine
                if (parts[1] !in TABLES || !parts[3].startsWith("{") || !parts[3].endsWith("}")) return@forEachLine
                maxSeq = maxOf(maxSeq, seq)
                if (seq > head && (entries.isEmpty() || seq > entries.last().seq)) {
                    entries.addLast(Entry(seq, parts[1], enqueuedAt, parts[3]))
                }
            }
        }
        trimParked()
        nextSeq = maxSeq + 1
        compact()
    }

    /** parked.log keeps the same 7-day bound as the queue. */
    private fun trimParked() {
        if (!parkedFile.exists()) {
            parked = 0
            return
        }
        val cutoff = clock() - maxAgeMillis
        val keep = parkedFile.readLines(Charsets.UTF_8).filter { line ->
            val enqueuedAt = line.split('\t', limit = 4).getOrNull(2)?.toLongOrNull()
            line.isNotBlank() && enqueuedAt != null && enqueuedAt >= cutoff
        }
        writeAtomically(parkedFile, keep.joinToString("") { "$it\n" })
        parked = keep.size
    }

    private fun persistHead() {
        writeAtomically(headFile, head.toString())
    }

    private fun maybeCompact() {
        if (entries.isEmpty() || doneSinceCompaction >= COMPACT_EVERY) compact()
    }

    /** Rewrites queue.log with only the undelivered rows. */
    private fun compact() {
        writeAtomically(queueFile, buildString {
            for (e in entries) append("${e.seq}\t${e.table}\t${e.enqueuedAt}\t${e.json}\n")
        })
        doneSinceCompaction = 0
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace ${target.name}" }
        }
    }

    companion object {
        private val open = HashMap<String, IngestQueue>()

        /**
         * The single in-process owner of [dir]. Every producer and worker for a directory
         * must share it, or two instances could compact over each other's rows.
         */
        @JvmStatic
        fun forDirectory(dir: File): IngestQueue = synchronized(open) {
            open.getOrPut(dir.canonicalPath) { IngestQueue(dir) }
        }

        const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val COMPACT_EVERY = 500
        val TABLES = setOf("detections", "aci_logs")
    }
}
