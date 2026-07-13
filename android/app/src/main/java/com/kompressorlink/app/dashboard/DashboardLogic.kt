package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.util.Locale

enum class GaugeLevel { NEUTRAL, OK, AMBER, RED, UNAVAILABLE }

data class GaugeUiState(
    val signal: Signal,
    val title: String,
    val valueText: String,   // "—" when unavailable
    val unit: String,
    val level: GaugeLevel,
    val bandText: String?,   // e.g. "3–5 g/s", null when no band applies
    val hint: String?,       // reference hint, only when AMBER/RED
    val history: List<Float>,
)

// Pure functions — everything the DashboardLogicTest covers lives here,
// framework-free.
object DashboardLogic {

    // Spec §4.5's gauge list, in display order.
    val DASHBOARD_SIGNALS = listOf(
        Signal.RPM, Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT,
        Signal.MAP, Signal.O2_B1S1_V, Signal.O2_B1S2_V, Signal.BATT_V_ADC,
        Signal.TIMING_ADV,
    )

    private val TITLES = mapOf(
        Signal.RPM to "RPM", Signal.MAF_GS to "MAF", Signal.STFT1 to "STFT",
        Signal.LTFT1 to "LTFT", Signal.ECT to "Coolant", Signal.MAP to "MAP",
        Signal.O2_B1S1_V to "O2 pre-cat", Signal.O2_B1S2_V to "O2 post-cat",
        Signal.BATT_V_ADC to "Battery", Signal.TIMING_ADV to "Timing",
    )

    private val UNITS = mapOf(
        Signal.RPM to "rpm", Signal.MAF_GS to "g/s", Signal.STFT1 to "%",
        Signal.LTFT1 to "%", Signal.ECT to "°C", Signal.MAP to "kPa",
        Signal.O2_B1S1_V to "V", Signal.O2_B1S2_V to "V",
        Signal.BATT_V_ADC to "V", Signal.TIMING_ADV to "°",
    )

    /** PLAN.md §7's warm-idle definition, computed from the snapshot itself.
     *  Any gating signal being unavailable means "not warm idle" — never
     *  guess a context from missing data (Untested contract). */
    fun isWarmIdle(s: TelemetrySnapshot): Boolean {
        val rpm = s.value(Signal.RPM) ?: return false
        val speed = s.value(Signal.SPEED) ?: return false
        val ect = s.value(Signal.ECT) ?: return false
        return rpm in 600f..900f && speed == 0f && ect > 80f
    }

    /** Plan-fixed thresholds: in-band OK; ≤ half-width beyond AMBER; else RED. */
    fun levelFor(value: Float, band: Band): GaugeLevel {
        val halfWidth = (band.hi - band.lo) / 2f
        return when {
            value >= band.lo && value <= band.hi -> GaugeLevel.OK
            value < band.lo - halfWidth || value > band.hi + halfWidth -> GaugeLevel.RED
            else -> GaugeLevel.AMBER
        }
    }

    fun applicableBand(signal: Signal, snapshot: TelemetrySnapshot, refs: ReferenceRepository): Band? =
        refs.bandsFor(signal).firstOrNull { band ->
            band.context == "always" || (band.context == "warm_idle" && isWarmIdle(snapshot))
        }

    fun gaugeFor(
        signal: Signal,
        snapshot: TelemetrySnapshot,
        refs: ReferenceRepository,
        history: List<Float>,
    ): GaugeUiState {
        val title = TITLES.getValue(signal)
        val unit = UNITS.getValue(signal)
        val value = snapshot.value(signal)
            ?: return GaugeUiState(signal, title, "—", unit, GaugeLevel.UNAVAILABLE,
                                   bandText = null, hint = null, history = emptyList())
        val band = applicableBand(signal, snapshot, refs)
        val level = band?.let { levelFor(value, it) } ?: GaugeLevel.NEUTRAL
        val decimals = if (signal == Signal.O2_B1S1_V || signal == Signal.O2_B1S2_V) 2 else 1
        return GaugeUiState(
            signal = signal,
            title = title,
            valueText = String.format(Locale.US, "%.${decimals}f", value),
            unit = unit,
            level = level,
            bandText = band?.let {
                String.format(Locale.US, "%.1f–%.1f %s (%s)", it.lo, it.hi, it.unit, it.confidence)
            },
            hint = if (level == GaugeLevel.AMBER || level == GaugeLevel.RED) band?.hint else null,
            history = history,
        )
    }
}
