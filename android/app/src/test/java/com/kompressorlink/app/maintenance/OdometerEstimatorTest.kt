package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.OdometerEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OdometerEstimatorTest {
    @Test
    fun noAnchor_noEstimate() {
        assertNull(OdometerEstimator.estimate(null, 100f))
    }

    @Test
    fun anchorPlusRoundedRealDistance() {
        val anchor = OdometerEntryEntity(id = 1, epochMs = 1_000, km = 186_900)
        val e = OdometerEstimator.estimate(anchor, 550.4f)!!
        assertEquals(187_450, e.km)
        assertEquals(186_900, e.anchorKm)
        assertEquals(550, e.estimatedDeltaKm)
    }

    @Test
    fun zeroDistance_isJustTheAnchor() {
        val anchor = OdometerEntryEntity(id = 1, epochMs = 1_000, km = 186_900)
        assertEquals(186_900, OdometerEstimator.estimate(anchor, 0f)!!.km)
    }
}
