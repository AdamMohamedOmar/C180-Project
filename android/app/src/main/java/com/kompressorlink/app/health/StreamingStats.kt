package com.kompressorlink.app.health

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Welford's online algorithm: numerically stable mean/stddev in O(1) memory.
class StreamingStats {
    var count: Int = 0
        private set
    var min: Float = Float.POSITIVE_INFINITY
        private set
    var max: Float = Float.NEGATIVE_INFINITY
        private set
    private var mean = 0.0
    private var m2 = 0.0

    fun add(v: Float) {
        count++
        min = min(min, v)
        max = max(max, v)
        val delta = v - mean
        mean += delta / count
        m2 += delta * (v - mean)
    }

    val meanValue: Float get() = if (count == 0) 0f else mean.toFloat()

    /** Population stddev; 0 for fewer than 2 samples. */
    val stdDev: Float get() = if (count < 2) 0f else sqrt(m2 / count).toFloat()
}
