package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardLogicTest {

    private fun asset(name: String): String =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
            .readText()

    private val refs = ReferenceRepository { asset(it) }
    private val band = Band("LTFT1", "always", -10f, 10f, "%", "Confirmed", "hint")

    @Test
    fun `level inside band is OK`() {
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(5f, band))
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(-10f, band))
    }

    @Test
    fun `level within half-width beyond band is AMBER, further is RED`() {
        // band -10..10, width 20, half-width 10 => amber to 20, red past it.
        assertEquals(GaugeLevel.AMBER, DashboardLogic.levelFor(18f, band))
        assertEquals(GaugeLevel.AMBER, DashboardLogic.levelFor(-15f, band))
        assertEquals(GaugeLevel.RED, DashboardLogic.levelFor(25f, band))
        assertEquals(GaugeLevel.RED, DashboardLogic.levelFor(-21f, band))
    }

    @Test
    fun `warm idle detected from the snapshot itself`() {
        val idle = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 0L, 0)
        assertTrue(DashboardLogic.isWarmIdle(idle))
    }

    @Test
    fun `warm idle is false when a gating signal is unavailable`() {
        // SPARSE keeps RPM/SPEED/ECT, so build one manually with ECT masked.
        val base = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 0L, 0)
        val noEct = com.kompressorlink.app.telemetry.TelemetrySnapshot(
            base.values, base.availMask and (1 shl Signal.ECT.ordinal).inv(),
            base.flags, base.seq, base.uptimeMs,
        )
        assertFalse(DashboardLogic.isWarmIdle(noEct))
    }

    @Test
    fun `fault scenario turns the LTFT gauge amber with the breather hint`() {
        val snapshot = FakeTelemetrySource.snapshotAt(FakeScenario.FAULT, 0L, 0)
        val gauge = DashboardLogic.gaugeFor(Signal.LTFT1, snapshot, refs, history = emptyList())
        assertEquals(GaugeLevel.AMBER, gauge.level)
        assertNotNull(gauge.hint)
        assertTrue(gauge.hint!!.contains("breather", ignoreCase = true))
    }

    @Test
    fun `sparse scenario renders MAP as unavailable, not zero`() {
        val snapshot = FakeTelemetrySource.snapshotAt(FakeScenario.SPARSE, 0L, 0)
        val gauge = DashboardLogic.gaugeFor(Signal.MAP, snapshot, refs, history = emptyList())
        assertEquals(GaugeLevel.UNAVAILABLE, gauge.level)
        assertEquals("—", gauge.valueText)
    }

    @Test
    fun `warm-idle band does not apply outside warm idle`() {
        // Healthy values but RPM forced to cruise: MAF's warm_idle band must
        // not judge a cruise MAF value.
        val base = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 0L, 0)
        base.values[Signal.RPM.ordinal] = 2600f
        base.values[Signal.MAF_GS.ordinal] = 18f  // way over the 3-5 idle band
        val gauge = DashboardLogic.gaugeFor(Signal.MAF_GS, base, refs, history = emptyList())
        assertEquals(GaugeLevel.NEUTRAL, gauge.level)
        assertNull(gauge.hint)
    }

    @Test
    fun `dashboard signal list matches the spec section 4-5`() {
        assertEquals(
            listOf(Signal.RPM, Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT,
                   Signal.MAP, Signal.O2_B1S1_V, Signal.O2_B1S2_V, Signal.BATT_V_ADC,
                   Signal.TIMING_ADV),
            DashboardLogic.DASHBOARD_SIGNALS,
        )
    }
}
