package org.magora.node

/**
 * Pauses BirdNET inference when the battery runs hot; capture, ACI and posting continue.
 * Pauses at >= [pauseAtC] and resumes only below [resumeBelowC] (hysteresis).
 */
class ThermalGovernor(
    private val pauseAtC: Double = 45.0,
    private val resumeBelowC: Double = 40.0,
) {
    enum class Transition { PAUSED, RESUMED }

    @Volatile
    var paused: Boolean = false
        private set

    /** Feeds one battery reading; returns the transition it caused, if any. */
    fun update(batteryTempC: Double): Transition? {
        // BatteryInfo reports -100.0 when the temperature extra is missing; ignore it.
        if (batteryTempC.isNaN() || batteryTempC <= -50.0) return null
        if (!paused && batteryTempC >= pauseAtC) {
            paused = true
            return Transition.PAUSED
        }
        if (paused && batteryTempC < resumeBelowC) {
            paused = false
            return Transition.RESUMED
        }
        return null
    }
}
