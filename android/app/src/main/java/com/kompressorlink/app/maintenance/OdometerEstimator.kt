package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.OdometerEntryEntity
import kotlin.math.roundToInt

// Spec §6.1: estimated km = latest manual anchor + Σ REAL session distances
// since that anchor. No anchor -> null -> km-based due math stays inactive
// ("Enter your odometer to activate"). With no hardware yet the delta is
// simply 0 — correct and honest.
object OdometerEstimator {
    data class Estimate(
        val km: Int,
        val anchorKm: Int,
        val anchorEpochMs: Long,
        val estimatedDeltaKm: Int,
    )

    fun estimate(anchor: OdometerEntryEntity?, realDistanceSinceAnchorKm: Float): Estimate? {
        if (anchor == null) return null
        val delta = realDistanceSinceAnchorKm.roundToInt()
        return Estimate(
            km = anchor.km + delta,
            anchorKm = anchor.km,
            anchorEpochMs = anchor.epochMs,
            estimatedDeltaKm = delta,
        )
    }
}
