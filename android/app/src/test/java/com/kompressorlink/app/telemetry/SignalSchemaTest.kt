package com.kompressorlink.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalSchemaTest {
    @Test
    fun `has exactly 19 signals`() {
        assertEquals(SIGNAL_COUNT, Signal.entries.size)
        assertEquals(19, SIGNAL_COUNT)
    }

    @Test
    fun `ordinals mirror the firmware enum`() {
        // Spot checks against firmware/src/pid_schema.h (mirror-verbatim
        // discipline; a reorder on either side must fail here).
        assertEquals(0, Signal.RPM.ordinal)
        assertEquals(7, Signal.MAP.ordinal)
        assertEquals(11, Signal.ECT.ordinal)
        assertEquals(13, Signal.FUEL_STATUS.ordinal)
        assertEquals(18, Signal.BATT_V_ADC.ordinal)
    }
}
