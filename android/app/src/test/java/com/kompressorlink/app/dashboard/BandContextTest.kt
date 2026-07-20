package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandContextTest {

    private val refs = ReferenceRepository { name -> File("src/main/assets/$name").readText() }

    private fun snap(vararg pairs: Pair<Signal, Float>): TelemetrySnapshot {
        val v = FloatArray(SIGNAL_COUNT)
        var mask = 0
        pairs.forEach { (s, value) ->
            v[s.ordinal] = value
            mask = mask or (1 shl s.ordinal)
        }
        return TelemetrySnapshot(v, mask, flags = 0, seq = 0, uptimeMs = 0)
    }

    @Test
    fun battery_chargingBand_whenEngineRunning() {
        val s = snap(Signal.RPM to 2000f, Signal.BATT_V_ADC to 14.2f)
        val band = DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs)!!
        assertEquals("engine_running", band.context)
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(14.2f, band))
    }

    @Test
    fun battery_weakAlternator_gapVoltage_gradesAmber() {
        // The two battery bands (engine_running 13.8–14.5, engine_off
        // 12.5–13.2) leave a gap between them. A weak-alternator car —
        // engine running but not yet at full charging voltage — falls in
        // that gap and must grade AMBER against engine_running, not RED
        // and not crash.
        val s = snap(Signal.RPM to 2000f, Signal.BATT_V_ADC to 13.5f)
        val band = DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs)!!
        assertEquals("engine_running", band.context)
        assertEquals(GaugeLevel.AMBER, DashboardLogic.levelFor(13.5f, band))
    }

    @Test
    fun battery_restingBand_whenEngineOff_healthyRestIsOk() {
        // The audit bug this fixes: a healthy parked car at 12.6 V used to
        // show RED against the charging band.
        val s = snap(Signal.RPM to 0f, Signal.BATT_V_ADC to 12.6f)
        val band = DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs)!!
        assertEquals("engine_off", band.context)
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(12.6f, band))
    }

    @Test
    fun battery_noBand_whenRpmUnavailable_rendersNeutral() {
        val s = snap(Signal.BATT_V_ADC to 12.6f)  // RPM masked out
        assertNull(DashboardLogic.applicableBand(Signal.BATT_V_ADC, s, refs))
        val gauge = DashboardLogic.gaugeFor(Signal.BATT_V_ADC, s, refs, emptyList())
        assertEquals(GaugeLevel.NEUTRAL, gauge.level)
    }

    @Test
    fun unknownContext_failsClosed() {
        val custom = ReferenceRepository { name ->
            when (name) {
                "w203_bands.json" -> """[
                    {"signal":"RPM","context":"moon_phase","lo":0.0,"hi":1.0,
                     "unit":"rpm","confidence":"Guessing","hint":"nope"}
                ]"""
                else -> File("src/main/assets/$name").readText()
            }
        }
        val s = snap(Signal.RPM to 750f)
        assertNull(DashboardLogic.applicableBand(Signal.RPM, s, custom))
    }

    @Test
    fun bandsJson_hasSevenEntries_batteryHasBothContexts() {
        // 6 original + LTFT1 "load_delta" (2026-07-17 enhancement plan, Task 5).
        assertEquals(7, refs.bands.size)
        val battery = refs.bandsFor(Signal.BATT_V_ADC)
        assertEquals(setOf("engine_running", "engine_off"), battery.map { it.context }.toSet())
    }

    @Test
    fun loadDelta_neverHoldsLive_ltftFallsBackToAlwaysBand() {
        // Step 2 (2026-07-17 enhancement plan): LTFT_LOAD_SENSITIVITY's
        // "load_delta" band exists for the health-metrics registry only. The
        // live dashboard's contextHolds() has no "load_delta" case, so it
        // must fail closed and the gauge must keep resolving to the "always"
        // band for LTFT1 — never crash, never silently pick the new band.
        val s = snap(Signal.RPM to 2000f, Signal.LTFT1 to 4f)
        val band = DashboardLogic.applicableBand(Signal.LTFT1, s, refs)!!
        assertEquals("always", band.context)
    }
}
