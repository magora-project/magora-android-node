package org.magora.node

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DailyLog {
    private val UTC_DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    /** ISO 8601 UTC timestamp with Z suffix; never depends on the device's time zone. */
    fun utcNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    fun append(context: Context, event: String) {
        val now = Instant.now()
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        val log = File(directory, "cycles-${UTC_DAY.format(now)}.log")
        log.appendText("${DateTimeFormatter.ISO_INSTANT.format(now)} $event\n")
    }
}
