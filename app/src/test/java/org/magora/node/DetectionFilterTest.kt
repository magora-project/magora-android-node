package org.magora.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFilterTest {
    private val rockWren = "Salpinctes obsoletus" to "Rock Wren"
    private val spottedCrake = "Porzana porzana" to "Spotted Crake"
    private val allow = setOf("${rockWren.first}_${rockWren.second}")

    @Test
    fun detectionAtExactlyTwentyHundredthsIsNotPosted() {
        val d = DetectionFilter.decide(rockWren.second, rockWren.first, 0.20f, null, null)
        assertFalse(d.post)
        assertEquals(DetectionFilter.Reason.FLOOR, d.reason)
    }

    @Test
    fun detectionJustAboveTheFloorIsPosted() {
        val d = DetectionFilter.decide(rockWren.second, rockWren.first, Math.nextUp(0.20f), null, null)
        assertTrue(d.post)
    }

    @Test
    fun belowTheFloorIsNotPosted() {
        assertEquals(DetectionFilter.Reason.FLOOR, DetectionFilter.decide("Rock Wren", "Salpinctes obsoletus", 0.13f, null, null).reason)
    }

    @Test
    fun everyExcludeLabelIsBlocked() {
        assertEquals(18, DetectionFilter.EXCLUDE.size)
        for (label in DetectionFilter.EXCLUDE) {
            val d = DetectionFilter.decide(label, label, 0.99f, null, null)
            assertFalse(label, d.post)
            assertEquals(label, DetectionFilter.Reason.EXCLUDE, d.reason)
        }
    }

    @Test
    fun excludeListMatchesTheContract() {
        val contract = setOf(
            "Human vocal", "Human non-vocal", "Human whistling", "Crowd", "Dog", "Cat", "Engine", "Siren",
            "Power tools", "Gun", "Fireworks", "Hand saw", "Chainsaw", "Car", "Truck", "Motorcycle",
            "Laysan Albatross", "Black-footed Albatross",
        )
        assertEquals(contract, DetectionFilter.EXCLUDE)
    }

    @Test
    fun insectCommonNamesAreBlocked() {
        for (name in listOf("Common True Katydid", "Snowy Tree Cricket", "Differential Grasshopper", "Dog-day Cicada")) {
            val d = DetectionFilter.decide(name, "Insecta sp", 0.95f, null, null)
            assertEquals(name, DetectionFilter.Reason.EXCLUDE, d.reason)
        }
    }

    @Test
    fun locationFilterDropsOffRangeSpecies() {
        val off = DetectionFilter.decide(spottedCrake.second, spottedCrake.first, 0.87f, allow, null)
        assertFalse(off.post)
        assertEquals(DetectionFilter.Reason.LOCATION, off.reason)
        assertTrue(DetectionFilter.decide(rockWren.second, rockWren.first, 0.87f, allow, null).post)
    }

    @Test
    fun emptyLocationListAllowsEverythingLikeBirdnetlib() {
        assertTrue(DetectionFilter.decide(spottedCrake.second, spottedCrake.first, 0.87f, emptySet(), null).post)
    }

    @Test
    fun whitelistIsCaseInsensitiveWithBypassAtSeventyHundredths() {
        val wl = setOf("rock wren")
        assertTrue(DetectionFilter.decide("Rock Wren", "Salpinctes obsoletus", 0.3f, null, wl).post)
        val canyon = DetectionFilter.decide("Canyon Wren", "Catherpes mexicanus", 0.69f, null, wl)
        assertEquals(DetectionFilter.Reason.WHITELIST, canyon.reason)
        val bypass = DetectionFilter.decide("Canyon Wren", "Catherpes mexicanus", 0.70f, null, wl)
        assertTrue(bypass.post)
        assertTrue(bypass.unusual)
    }

    @Test
    fun noWhitelistMeansNoSpeciesFiltering() {
        assertTrue(DetectionFilter.decide("Canyon Wren", "Catherpes mexicanus", 0.25f, null, null).post)
    }

    @Test
    fun excludeWinsOverWhitelistBypass() {
        val d = DetectionFilter.decide("Engine", "Engine", 0.99f, null, setOf("rock wren"))
        assertEquals(DetectionFilter.Reason.EXCLUDE, d.reason)
    }
}
