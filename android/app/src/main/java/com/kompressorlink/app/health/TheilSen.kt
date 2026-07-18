package com.kompressorlink.app.health

// Median of all pairwise slopes — robust to outlier sessions in a way
// least-squares is not (spec §5.3).
object TheilSen {
    /** Slope in value-units per DAY; null when < 2 distinct-time points. */
    fun slopePerDay(points: List<MetricPoint>): Float? {
        val slopes = ArrayList<Float>()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val dtDays = (points[j].endedAtEpochMs - points[i].endedAtEpochMs) / Baseline.DAY_MS.toFloat()
                if (dtDays != 0f) {
                    slopes.add((points[j].value - points[i].value) / dtDays)
                }
            }
        }
        return if (slopes.isEmpty()) null else Baseline.median(slopes)
    }
}
