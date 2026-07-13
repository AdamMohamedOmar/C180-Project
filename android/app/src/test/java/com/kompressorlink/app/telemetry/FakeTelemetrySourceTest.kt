package com.kompressorlink.app.telemetry

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTelemetrySourceTest {

    @Test
    fun `healthy scenario serves the demo mask and in-band values`() {
        val s = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, tMs = 1000L, seq = 2)
        assertEquals(FakeTelemetrySource.HEALTHY_MASK, s.availMask)
        assertTrue(s.value(Signal.MAF_GS)!! in 3f..5f)      // w203 warm-idle band
        assertTrue(s.value(Signal.LTFT1)!! in -10f..10f)
        assertEquals(14.25f, s.value(Signal.BATT_V_ADC))
        assertNull(s.value(Signal.PEDAL_D))                  // Legacy: never provided
    }

    @Test
    fun `fault scenario pushes LTFT out of band and reports P0171`() {
        val s = FakeTelemetrySource.snapshotAt(FakeScenario.FAULT, tMs = 0L, seq = 0)
        assertEquals(18f, s.value(Signal.LTFT1))
        val source = FakeTelemetrySource(FakeScenario.FAULT)
        assertEquals(DtcReport(listOf("P0171"), emptyList()), source.dtcReport.value)
    }

    @Test
    fun `sparse scenario masks out MAP and both O2 sensors with zeroed slots`() {
        val s = FakeTelemetrySource.snapshotAt(FakeScenario.SPARSE, tMs = 0L, seq = 0)
        assertFalse(s.isAvailable(Signal.MAP))
        assertFalse(s.isAvailable(Signal.O2_B1S1_V))
        assertFalse(s.isAvailable(Signal.O2_B1S2_V))
        assertNull(s.value(Signal.MAP))
        assertEquals(0f, s.values[Signal.MAP.ordinal])  // contract parity with firmware packer
        assertTrue(s.isAvailable(Signal.RPM))           // everything else still there
    }

    @Test
    fun `is deterministic in t`() {
        val a = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 12345L, 7)
        val b = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 12345L, 7)
        assertTrue(a.values.contentEquals(b.values))
        assertEquals(a.availMask, b.availMask)
    }

    @Test
    fun `flow ticks emit consecutive seq`() = runTest {
        val source = FakeTelemetrySource(FakeScenario.HEALTHY)
        val two = source.telemetry.take(2).toList()  // virtual time — no real 500 ms waits
        assertEquals(two[0].seq + 1, two[1].seq)
    }

    @Test
    fun `healthy and sparse report no dtcs`() {
        assertEquals(DtcReport(emptyList(), emptyList()),
                     FakeTelemetrySource(FakeScenario.HEALTHY).dtcReport.value)
        assertEquals(DtcReport(emptyList(), emptyList()),
                     FakeTelemetrySource(FakeScenario.SPARSE).dtcReport.value)
    }
}
