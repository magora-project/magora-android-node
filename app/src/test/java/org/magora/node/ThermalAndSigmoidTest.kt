package org.magora.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalAndSigmoidTest {

    @Test
    fun pausesAtFortyFiveAndResumesBelowForty() {
        val t = ThermalGovernor()
        assertNull(t.update(44.9))
        assertEquals(ThermalGovernor.Transition.PAUSED, t.update(45.0))
        assertTrue(t.paused)
        assertNull(t.update(46.0))
        assertNull(t.update(40.0)) // hysteresis: still paused at exactly 40
        assertTrue(t.paused)
        assertEquals(ThermalGovernor.Transition.RESUMED, t.update(39.9))
        assertFalse(t.paused)
    }

    @Test
    fun missingTemperatureIsIgnored() {
        val t = ThermalGovernor()
        t.update(50.0)
        assertNull(t.update(-100.0))
        assertTrue(t.paused)
    }

    @Test
    fun sigmoidMatchesBirdnetlibAtSensitivityOnePointTwoFive() {
        assertEquals(0.75f, BirdnetAnalyzer.EFFECTIVE_SENSITIVITY, 0f)
        // numpy 2.5.2: 1 / (1.0 + np.exp(-0.75 * np.clip(x, -15, 15))) on float32 logits.
        val expected = mapOf(
            -20f to 1.3007128472963814e-05f, -3.2f to 0.08317269384860992f, -1f to 0.32082128524780273f,
            0f to 0.5f, 0.5f to 0.5926666259765625f, 1f to 0.6791787147521973f, 4f to 0.9525741338729858f,
            30f to 0.999987006187439f,
        )
        for ((logit, p) in expected) {
            assertEquals("logit $logit", p, BirdnetAnalyzer.sigmoid(logit), 2e-7f)
        }
    }
}
