package com.kompressorlink.app.health

// Streaming building blocks for the dynamic M271 features (2026-07-17
// enhancement plan). All O(1) memory — session raw samples are never stored
// (4.5 design rule).

/**
 * Ordinary least-squares slope, accumulated one (x, y) at a time via
 * Welford-style online covariance — the same numerically-stable family as
 * the sibling [StreamingStats]. A naive sum-of-squares degeneracy check
 * (`n*sumXX - sumX*sumX`) suffers catastrophic cancellation once n and the
 * magnitude of x grow to this app's realistic session scale (thousands of
 * snapshots; x up to ~2 h expressed in minutes): the floating-point
 * residual left over from a genuinely-degenerate x can land many orders of
 * magnitude above any fixed near-zero threshold, so the guard silently
 * fails to fire and a plausible-looking wrong slope comes back instead of
 * null. The centered running form below has no large-number cancellation
 * — `sXX` near zero is numerically trustworthy at any n or x scale.
 */
class StreamingSlope {
    var count = 0
        private set
    private var meanX = 0.0
    private var meanY = 0.0
    private var sXY = 0.0   // running n * covariance(x, y)
    private var sXX = 0.0   // running n * variance(x)

    fun add(xMinutes: Float, y: Float) {
        val x = xMinutes.toDouble()
        val yd = y.toDouble()
        count++
        val dx = x - meanX
        meanX += dx / count
        meanY += (yd - meanY) / count
        sXY += dx * (yd - meanY)
        sXX += dx * (x - meanX)
    }

    /** Slope in y-units per x-unit; null under 2 points or degenerate x. */
    val slopePerX: Float?
        get() {
            if (count < 2) return null
            if (sXX <= 1e-9) return null
            return (sXY / sXX).toFloat()
        }
}

/**
 * Debounce for snapshot contexts: active only after [minConsecutive]
 * consecutive `true` updates. `false` AND `null` (gating signal
 * unavailable) both reset — a context is never inferred through missing
 * data.
 */
class DwellGate(private val minConsecutive: Int) {
    init {
        require(minConsecutive > 0) {
            "minConsecutive must be positive, was $minConsecutive"
        }
    }

    private var run = 0

    fun update(active: Boolean?): Boolean {
        run = if (active == true) run + 1 else 0
        return run >= minConsecutive
    }
}

/**
 * Time from session start until the pre-cat narrowband O2 has visited BOTH
 * rails (>= O2_SWING_HIGH_V and <= O2_SWING_LOW_V, either order) — the
 * first completed rich/lean swing, i.e. the sensor is demonstrably active.
 * Honest resolution at the 0.5 Hz poll / 2 Hz repeat cadence is ±2–4 s,
 * which is fine against the tens-of-seconds drift of a degrading heater
 * circuit. This is ONSET, not a heater measurement — copy must never claim
 * a heater diagnosis.
 */
class O2ActivityOnsetDetector {
    private var seenLow = false
    private var seenHigh = false

    var onsetS: Float? = null
        private set

    fun add(tSinceStartS: Float, o2Volts: Float) {
        if (onsetS != null) return
        if (o2Volts <= HealthTuning.O2_SWING_LOW_V) seenLow = true
        if (o2Volts >= HealthTuning.O2_SWING_HIGH_V) seenHigh = true
        if (seenLow && seenHigh) onsetS = tSinceStartS
    }
}
