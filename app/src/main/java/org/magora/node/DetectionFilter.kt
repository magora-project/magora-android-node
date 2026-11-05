package org.magora.node

/**
 * Decides whether a raw BirdNET detection is posted. The local log stays raw; every
 * detection is logged with this decision.
 *
 * Order: confidence floor, BirdNET location filter, fixed exclusions, species whitelist.
 * The floor, exclusions and whitelist follow docs/node-contract.md and detect.py at
 * f0c1940. The location filter is birdnetlib's (lat/lon/week species list); deployed Pi
 * nodes run with it off, so this node filters more than they do.
 */
object DetectionFilter {

    /** Posted only when confidence is strictly greater than this (compared in float32). */
    const val MIN_CONF = 0.20f

    /**
     * Species absent from the whitelist are kept only at or above this confidence.
     *
     * Both thresholds compare the model's float32 confidence against a float32 constant.
     * detect.py compares float(np.float32) against a Python double, which differs only at
     * the two exact float32 boundary values: there a float32 0.2 passes the floor and a
     * float32 0.7 misses the bypass. Here 0.20 is not posted and 0.70 is, as the contract
     * and the Phase 2 spec state.
     */
    const val WHITELIST_BYPASS_CONF = 0.70f

    /** detect.py EXCLUDE, verbatim. */
    val EXCLUDE: Set<String> = setOf(
        "Human vocal", "Human non-vocal", "Human whistling", "Crowd",
        "Dog", "Cat",
        "Engine", "Siren", "Power tools", "Gun",
        "Fireworks", "Hand saw", "Chainsaw",
        "Car", "Truck", "Motorcycle",
        // Seabirds BirdNET confuses with Common Raven calls
        "Laysan Albatross", "Black-footed Albatross",
    )

    /** detect.py insect filter: substring match on the common name. */
    val INSECT_KEYWORDS: List<String> = listOf("Katydid", "Cricket", "Grasshopper", "Cicada")

    enum class Reason(val logName: String) {
        FLOOR("floor"),
        LOCATION("location"),
        EXCLUDE("exclude"),
        WHITELIST("whitelist"),
    }

    data class Decision(val post: Boolean, val reason: Reason?, val unusual: Boolean = false)

    /**
     * @param locationAllow birdnetlib allow-list keys ("Scientific name_Common name"), or
     *   null when the location filter is not active. An empty list allows everything, as
     *   in birdnetlib.
     * @param whitelist lower-cased common names, or null when no whitelist is available.
     */
    fun decide(
        commonName: String,
        scientificName: String,
        confidence: Float,
        locationAllow: Set<String>?,
        whitelist: Set<String>?,
    ): Decision {
        if (!(confidence > MIN_CONF)) return Decision(false, Reason.FLOOR)
        if (locationAllow != null && locationAllow.isNotEmpty() && "${scientificName}_$commonName" !in locationAllow) {
            return Decision(false, Reason.LOCATION)
        }
        if (commonName in EXCLUDE || INSECT_KEYWORDS.any { it in commonName }) {
            return Decision(false, Reason.EXCLUDE)
        }
        if (whitelist != null && commonName.lowercase() !in whitelist) {
            if (confidence < WHITELIST_BYPASS_CONF) return Decision(false, Reason.WHITELIST)
            return Decision(true, null, unusual = true)
        }
        return Decision(true, null)
    }
}
