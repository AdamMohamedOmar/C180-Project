package com.kompressorlink.app.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicFeaturesTest {

    @Test
    fun `slope recovers a clean linear ramp`() {
        val s = StreamingSlope()
        // ECT 20 -> 80 °C over 10 min = 6 °C/min
        for (i in 0..600) s.add(xMinutes = i / 60f, y = 20f + i * 0.1f)
        assertEquals(6f, s.slopePerX!!, 0.01f)
    }

    @Test
    fun `slope survives step-wise repeated values`() {
        // The BLE stream repeats a 0.1 Hz-polled ECT at 2 Hz: 20 identical
        // snapshots per fresh sample. Least squares over the steps must
        // still recover the underlying warm-up rate. The first and last
        // steps carry a +/-2 °C bump so a naive endpoint-difference slope
        // ((last y - first y) / (last x - first x), ~10.8 °C/min by hand
        // calculation) lands outside this test's tolerance while true OLS
        // still recovers ~12 -- on the original unperturbed ramp both
        // approaches agree, so that version couldn't actually tell a real
        // least-squares fit apart from a cheap endpoint approximation.
        val s = StreamingSlope()
        var t = 0f
        for (step in 0 until 30) {
            var ect = 20f + step * 2f       // +2 °C per 10 s = 12 °C/min
            if (step == 0) ect += 2f
            if (step == 29) ect -= 2f
            repeat(20) { s.add(t / 60f, ect); t += 0.5f }
        }
        assertEquals(12f, s.slopePerX!!, 0.5f)
    }

    @Test
    fun `slope null below two points or zero x variance`() {
        val s = StreamingSlope()
        assertNull(s.slopePerX)
        s.add(1f, 10f)
        assertNull(s.slopePerX)
        s.add(1f, 12f)  // same x
        assertNull(s.slopePerX)
    }

    @Test
    fun `slope stays null at realistic scale when x is genuinely frozen`() {
        // Regression guard for a real bug: a naive degeneracy check built
        // on `n*sumXX - sumX*sumX` suffers catastrophic cancellation once
        // n and the magnitude of x grow to this app's realistic session
        // scale -- the floating-point residual left over from a
        // genuinely-degenerate x can land many orders of magnitude above
        // a fixed 1e-9 threshold, so the guard silently fails to fire and
        // a plausible-looking wrong slope comes back instead of null.
        // Hand-verified: at n=14_400 and x~100-120 (this class's realistic
        // scale), the naive residual routinely lands in the 1e-3 to 1
        // range, not near 1e-9; this specific case was confirmed to leave
        // a residual of ~1.09.
        //
        // Mirrors the real call site: xMinutes = atMs / 60_000f with atMs
        // pinned to a single elapsed-time value (e.g. a stuck clock read)
        // for a full ~2 h, 2 Hz session (14 400 snapshots) -- x never
        // moves even though y (a fake trend, standing in for ECT) does.
        val s = StreamingSlope()
        val atMs = 7_285_284L
        val xMinutes = atMs / 60_000f
        for (i in 0 until 14_400) {
            s.add(xMinutes, 20f + i * 0.005f)
        }
        assertNull(s.slopePerX)
    }

    @Test
    fun `dwell gate opens after N consecutive and resets on false or null`() {
        val g = DwellGate(3)
        assertFalse(g.update(true)); assertFalse(g.update(true))
        assertTrue(g.update(true))
        assertTrue(g.update(true))          // stays open while held
        assertFalse(g.update(false))        // reset
        assertFalse(g.update(true)); assertFalse(g.update(true))
        assertFalse(g.update(null))         // unknown also resets (Untested contract)
        assertFalse(g.update(true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dwell gate rejects a non-positive minConsecutive`() {
        DwellGate(0)
    }

    @Test
    fun `o2 onset fires at first completed rich-lean pair`() {
        val d = O2ActivityOnsetDetector()
        d.add(2f, 0.45f)    // mid-band: nothing
        d.add(4f, 0.75f)    // rich rail seen
        assertNull(d.onsetS)
        d.add(6f, 0.15f)    // lean rail seen -> full swing complete
        assertEquals(6f, d.onsetS!!, 1e-3f)
        d.add(8f, 0.8f)     // latched — later swings don't move it
        assertEquals(6f, d.onsetS!!, 1e-3f)
    }

    @Test
    fun `o2 onset order-independent (lean first also counts)`() {
        val d = O2ActivityOnsetDetector()
        d.add(3f, 0.1f)
        d.add(9f, 0.7f)
        assertEquals(9f, d.onsetS!!, 1e-3f)
    }
}
