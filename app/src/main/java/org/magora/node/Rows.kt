package org.magora.node

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Supabase row payloads, shaped exactly as docs/node-contract.md (and detect.py at f0c1940)
 * post them. Built as JSON text so the queue can persist them verbatim.
 */
object Rows {

    data class Site(val nodeId: String, val lat: Double, val lon: Double)

    fun detection(
        site: Site,
        commonName: String,
        scientificName: String,
        confidence: Float,
        detectedAt: Instant,
        temporal: Temporal.Context,
    ): String = json(
        "node_id" to site.nodeId,
        "species_name" to commonName,
        "raw_label" to scientificName,
        "confidence" to roundHalfEven(confidence.toDouble(), 2),
        "detected_at" to timestamp(detectedAt),
        "location" to "POINT(${site.lon} ${site.lat})",
        // detect.py: temporal.get("dawn_chorus_window") or dawn
        "is_dawn_chorus" to temporal.dawn,
        "minutes_from_sunrise" to temporal.minutesFromSunrise,
        "dawn_chorus_window" to temporal.dawnChorusWindow,
        "phenological_week" to temporal.phenologicalWeek,
        "season" to temporal.season,
    )

    fun aci(site: Site, aciScore: Double, recordedAt: Instant, temporal: Temporal.Context): String = json(
        "node_id" to site.nodeId,
        "recorded_at" to timestamp(recordedAt),
        "aci_score" to aciScore,
        "time_category" to temporal.timeCategory,
        "dawn_chorus" to temporal.dawn,
        "duration_secs" to 15,
    )

    fun timestamp(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    /** Python round(x, n) on a float: round the exact binary value half-to-even. */
    fun roundHalfEven(value: Double, places: Int): Double =
        BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toDouble()

    private fun json(vararg fields: Pair<String, Any?>): String = buildString {
        append('{')
        fields.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendString(key)
            append(':')
            when (value) {
                null -> append("null")
                is String -> appendString(value)
                is Boolean, is Int, is Long -> append(value.toString())
                is Double -> {
                    require(value.isFinite()) { "non-finite number for $key" }
                    append(value.toString())
                }
                else -> error("unsupported JSON value for $key")
            }
        }
        append('}')
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
