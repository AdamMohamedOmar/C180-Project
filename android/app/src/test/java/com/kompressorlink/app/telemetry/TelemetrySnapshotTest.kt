package com.kompressorlink.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySnapshotTest {
    private fun snapshot(mask: Int, flags: Int = 0): TelemetrySnapshot {
        val values = FloatArray(SIGNAL_COUNT)
        values[Signal.RPM.ordinal] = 750f
        values[Signal.ECT.ordinal] = 90f
        return TelemetrySnapshot(values, mask, flags, seq = 1, uptimeMs = 1000L)
    }

    @Test
    fun `value returns reading only when mask bit set`() {
        val s = snapshot(mask = 1 shl Signal.RPM.ordinal)
        assertEquals(750f, s.value(Signal.RPM))
        // ECT has a (stale) value in the array but its bit is clear — the
        // Untested contract says that must surface as "no reading".
        assertNull(s.value(Signal.ECT))
        assertFalse(s.isAvailable(Signal.ECT))
    }

    @Test
    fun `flag helpers decode the protocol flag bits`() {
        assertTrue(snapshot(0, flags = 0x01).isDemo)
        assertFalse(snapshot(0, flags = 0x01).klineConnected)
        assertTrue(snapshot(0, flags = 0x02).klineConnected)
    }
}
