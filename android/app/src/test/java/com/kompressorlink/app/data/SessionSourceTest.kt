package com.kompressorlink.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSourceTest {

    @Test
    fun `ride sources quarantine correctly`() {
        assertTrue(SessionSource.REAL_RIDE.isReal)
        assertFalse(SessionSource.SIM_RIDE.isReal)
        assertTrue(SessionSource.REAL.contains(SessionSource.REAL_RIDE))
        assertTrue(SessionSource.SIM.contains(SessionSource.SIM_RIDE))
    }
}
