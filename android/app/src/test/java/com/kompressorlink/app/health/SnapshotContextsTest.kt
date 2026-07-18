package com.kompressorlink.app.health

import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotContextsTest {

    // Same snapshot-builder helper as SessionAggregatorTest.
    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test fun `highLoad true only when both gates met`() {
        assertEquals(true, SnapshotContexts.highLoad(snap(Signal.THROTTLE to 80f, Signal.RPM to 3500f)))
        assertEquals(false, SnapshotContexts.highLoad(snap(Signal.THROTTLE to 80f, Signal.RPM to 2000f)))
        assertEquals(false, SnapshotContexts.highLoad(snap(Signal.THROTTLE to 30f, Signal.RPM to 3500f)))
    }

    @Test fun `highLoad null when a gating signal is unavailable`() {
        assertNull(SnapshotContexts.highLoad(snap(Signal.THROTTLE to 80f)))   // RPM missing
        assertNull(SnapshotContexts.highLoad(snap(Signal.RPM to 3500f)))      // THROTTLE missing
    }
}
