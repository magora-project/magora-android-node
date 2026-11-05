package org.magora.node

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime

object DailyLog {
    fun append(context: Context, event: String) {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        val log = File(directory, "cycles-${LocalDate.now()}.log")
        log.appendText("${OffsetDateTime.now()} $event\n")
    }
}
