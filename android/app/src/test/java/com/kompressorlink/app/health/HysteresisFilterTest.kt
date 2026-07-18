package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class HysteresisFilterTest {

    @Test
    fun firstSample_displaysImmediately() {
        val f = HysteresisFilter()
        assertEquals(GaugeLevel.AMBER, f.update(GaugeLevel.AMBER, 0))
    }

    @Test
    fun escalation_requiresSustainedWorseLevel() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.OK, 0)
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.AMBER, 500))       // pending starts at 500
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.AMBER, 3_000))     // 2.5 s < 3 s
        assertEquals(GaugeLevel.AMBER, f.update(GaugeLevel.AMBER, 3_500))  // 3.0 s -> escalate
    }

    @Test
    fun flicker_atBandEdge_isSuppressed() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.OK, 0)
        var t = 500L
        repeat(20) {  // OK/AMBER alternating every 500 ms — never sustained
            assertEquals(GaugeLevel.OK, f.update(GaugeLevel.AMBER, t)); t += 500
            assertEquals(GaugeLevel.OK, f.update(GaugeLevel.OK, t)); t += 500
        }
    }

    @Test
    fun deescalation_takesLonger() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.RED, 0)
        assertEquals(GaugeLevel.RED, f.update(GaugeLevel.OK, 1_000))   // pending from 1 000
        assertEquals(GaugeLevel.RED, f.update(GaugeLevel.OK, 10_500))  // 9.5 s < 10 s
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.OK, 11_000))   // 10 s -> de-escalate
    }

    @Test
    fun pendingLevelChange_restartsTimer() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.OK, 0)
        f.update(GaugeLevel.AMBER, 500)                                 // pending AMBER
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.RED, 2_500))    // pending restarts as RED
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.RED, 5_000))    // 2.5 s < 3 s
        assertEquals(GaugeLevel.RED, f.update(GaugeLevel.RED, 5_500))   // 3 s of RED
    }

    @Test
    fun unavailable_passesThrough_andResets() {
        val f = HysteresisFilter()
        f.update(GaugeLevel.RED, 0)
        assertEquals(GaugeLevel.UNAVAILABLE, f.update(GaugeLevel.UNAVAILABLE, 500))
        // Coming back: first sample after UNAVAILABLE displays immediately.
        assertEquals(GaugeLevel.OK, f.update(GaugeLevel.OK, 1_000))
    }
}
