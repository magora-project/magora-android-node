package org.magora.node

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RowsTest {
    private val site = Rows.Site("04d3e4f2-cb4f-4b33-8fbe-a9548143933d", 29.4607, -103.655)
    private val at = Instant.parse("2026-11-05T16:16:37.008126Z")

    @Test
    fun detectionRowHasExactlyTheContractColumns() {
        val temporal = Temporal.Context("Morning", 42, true, 45, "late_fall")
        val json = Rows.detection(site, "Rock Wren", "Salpinctes obsoletus", 0.8765f, at, temporal)
        assertEquals(
            "{\"node_id\":\"04d3e4f2-cb4f-4b33-8fbe-a9548143933d\",\"species_name\":\"Rock Wren\"," +
                "\"raw_label\":\"Salpinctes obsoletus\",\"confidence\":0.88,\"detected_at\":\"2026-11-05T16:16:37.008126Z\"," +
                "\"location\":\"POINT(-103.655 29.4607)\",\"is_dawn_chorus\":true,\"minutes_from_sunrise\":42," +
                "\"dawn_chorus_window\":true,\"phenological_week\":45,\"season\":\"late_fall\"}",
            json,
        )
    }

    @Test
    fun detectionRowCarriesNullsWhenAstralFailed() {
        val temporal = Temporal.Context("Night", null, null, null, null)
        val json = Rows.detection(site, "Rock Wren", "Salpinctes obsoletus", 0.5f, at, temporal)
        assertEquals(true, json.contains("\"is_dawn_chorus\":false,\"minutes_from_sunrise\":null,\"dawn_chorus_window\":null,\"phenological_week\":null,\"season\":null}"))
    }

    @Test
    fun aciRowHasExactlyTheContractColumns() {
        val temporal = Temporal.Context("Dawn", -10, true, 45, "late_fall")
        assertEquals(
            "{\"node_id\":\"04d3e4f2-cb4f-4b33-8fbe-a9548143933d\",\"recorded_at\":\"2026-11-05T16:16:37.008126Z\"," +
                "\"aci_score\":0.604,\"time_category\":\"Dawn\",\"dawn_chorus\":true,\"duration_secs\":15}",
            Rows.aci(site, 0.604, at, temporal),
        )
    }

    @Test
    fun confidenceRoundsLikePython() {
        // Python: round(0.125, 2) == 0.12, round(0.675, 2) == 0.68, round(0.205, 2) == 0.2, round(0.995, 2) == 0.99
        assertEquals(0.12, Rows.roundHalfEven(0.125, 2), 0.0)
        assertEquals(0.68, Rows.roundHalfEven(0.675, 2), 0.0)
        assertEquals(0.2, Rows.roundHalfEven(0.205, 2), 0.0)
        assertEquals(0.99, Rows.roundHalfEven(0.995, 2), 0.0)
        assertEquals(0.31, Rows.roundHalfEven(0.31415926, 2), 0.0)
    }

    @Test
    fun speciesNamesAreJsonEscaped() {
        val json = Rows.detection(site, "Says \"hi\"\\", "Sci", 0.5f, at, Temporal.Context("Night", null, null, null, null))
        assertEquals(true, json.contains("\"species_name\":\"Says \\\"hi\\\"\\\\\""))
    }
}
