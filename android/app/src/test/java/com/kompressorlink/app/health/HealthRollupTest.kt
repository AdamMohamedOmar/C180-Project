package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRollupTest {

    private fun cards(
        levels: Map<Signal, GaugeLevel> = emptyMap(),
        warnings: List<WarningEntity> = emptyList(),
        dtc: DtcReport? = null,
        drift: Map<MetricId, Drift.Result> = emptyMap(),
        baseline: Map<MetricId, Baseline.Result> = emptyMap(),
    ) = HealthRollup.cards(levels, warnings, dtc, drift, baseline)

    private fun card(cards: List<SubsystemCard>, s: Subsystem) = cards.first { it.subsystem == s }

    @Test
    fun allQuiet_everyCardOk_otherHidden() {
        val result = cards()
        assertEquals(6, result.size)  // OTHER hidden when empty
        assertTrue(result.all { it.status == HealthStatus.OK && it.reason == "Looks normal" })
        assertEquals(Subsystem.IGNITION_FOOTNOTE, card(result, Subsystem.IGNITION).footnote)
        assertNull(card(result, Subsystem.FUELING).footnote)
    }

    @Test
    fun storedDtc_forcesAttention_onItsSubsystem() {
        val result = cards(dtc = DtcReport(stored = listOf("P0171"), pending = emptyList()))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.ATTENTION, fueling.status)
        assertTrue(fueling.reason.contains("P0171"))
    }

    @Test
    fun unmappedDtc_createsOtherCard() {
        val result = cards(dtc = DtcReport(stored = emptyList(), pending = listOf("P1570")))
        val other = card(result, Subsystem.OTHER)
        assertEquals(HealthStatus.WATCH, other.status)
        assertTrue(other.reason.contains("P1570"))
    }

    @Test
    fun liveLevels_mapToStatuses_noDataIsNeverAFault() {
        val result = cards(levels = mapOf(
            Signal.LTFT1 to GaugeLevel.RED,
            Signal.MAF_GS to GaugeLevel.AMBER,
            Signal.ECT to GaugeLevel.UNAVAILABLE,
            Signal.BATT_V_ADC to GaugeLevel.NEUTRAL,
        ))
        assertEquals(HealthStatus.ATTENTION, card(result, Subsystem.FUELING).status)
        assertEquals(HealthStatus.WATCH, card(result, Subsystem.AIR_INTAKE).status)
        assertEquals(HealthStatus.OK, card(result, Subsystem.COOLING).status)
        assertEquals(HealthStatus.OK, card(result, Subsystem.BATTERY).status)
    }

    @Test
    fun drift_setsArrowAndWatch() {
        val drifting = Drift.Result.Drifting(
            slopePerWeek = 0.7f, weeksToEdge = 3, rising = true, message = "msg",
        )
        val result = cards(drift = mapOf(MetricId.LTFT_WARM_IDLE to drifting))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)
        assertEquals('↗', fueling.driftArrow)
        assertTrue(fueling.reason.contains("~3 weeks"))
    }

    @Test
    fun drift_oppositeDirectionsOnSameSubsystem_arrowMatchesReportedReason() {
        // FUELING owns two driftable metrics (LTFT_WARM_IDLE, STFT_WARM_IDLE).
        // If they drift in opposite directions, driftArrow must reflect
        // whichever metric's text actually won the `reason` field — never a
        // different metric's direction (that would show e.g. a down-arrow
        // next to "... drifting up ...").
        val rising = Drift.Result.Drifting(
            slopePerWeek = 0.7f, weeksToEdge = 3, rising = true, message = "msg-rising",
        )
        val falling = Drift.Result.Drifting(
            slopePerWeek = -0.5f, weeksToEdge = 5, rising = false, message = "msg-falling",
        )
        val result = cards(drift = mapOf(
            MetricId.LTFT_WARM_IDLE to rising,
            MetricId.STFT_WARM_IDLE to falling,
        ))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)

        val reasonSaysUp = fueling.reason.contains("drifting up")
        val reasonSaysDown = fueling.reason.contains("drifting down")
        assertTrue(reasonSaysUp != reasonSaysDown)  // exactly one, never both/neither
        assertEquals(if (reasonSaysUp) '↗' else '↘', fueling.driftArrow)
    }

    @Test
    fun drift_setsArrowAndWatch_forNewLoadSensitivityMetric() {
        // LTFT_LOAD_SENSITIVITY (2026-07-17 enhancement plan) is a brand-new
        // MetricId sharing FUELING with LTFT_WARM_IDLE/STFT_WARM_IDLE. This
        // proves the exact same generic per-subsystem drift rollup exercised
        // above by drift_setsArrowAndWatch (for the 4.5-era LTFT_WARM_IDLE)
        // also fires correctly for the new metric -- HealthRollup never
        // special-cases a MetricId by name, so a new enum entry composes for
        // free. Falling direction: a load-sensitivity delta trending toward
        // the -8 "load_delta" edge is the classic M271 breather-leak
        // signature (PLAN.md §8).
        //
        // Note what this does NOT prove: HealthRollup's own contribution
        // text never carries a unit (it only uses weeksToEdge/rising, never
        // Drift.Result.message) -- see DriftTest for the actual "%/week"
        // unit-composition proof, which lives in a different string used by
        // a different UI surface (the Warnings list's detail text, not this
        // card's reason).
        val drifting = Drift.Result.Drifting(
            slopePerWeek = -0.6f, weeksToEdge = 4, rising = false, message = "msg",
        )
        val result = cards(drift = mapOf(MetricId.LTFT_LOAD_SENSITIVITY to drifting))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)
        assertEquals('↘', fueling.driftArrow)
        assertTrue(fueling.reason.contains("drifting down"))
        assertTrue(fueling.reason.contains("~4 weeks"))
    }

    @Test
    fun openWarning_forBandlessNewMetric_surfacesAsSubsystemReason_neverAttention_neverHeaterClaim() {
        // O2_ACTIVITY_ONSET (2026-07-17 enhancement plan) has no absolute
        // band -- it ships baseline-only (Metrics.kt/w203_bands.json) -- so
        // its ONLY route to the user is a PostSessionEvaluator
        // BASELINE_DEVIATION WarningEntity flowing back through this
        // openWarnings contribution. That contribution path had NO test
        // coverage anywhere in this file before this change, for ANY metric
        // (every existing test here passes the `warnings` param as its
        // empty default). This proves: (1) an open warning for a band-less
        // new metric correctly becomes its owning subsystem's card reason,
        // (2) it surfaces at WATCH, never ATTENTION -- PostSessionEvaluator
        // hardcodes level="WATCH" for both BASELINE_DEVIATION and DRIFT;
        // ATTENTION is only ever raised by LiveWarningMonitor's live
        // RED-gauge or DTC paths, never by baseline/drift -- and (3) the
        // surfaced text never claims a heater diagnosis (it's onset, not a
        // heater measurement -- CLAUDE.md scope-honesty rule).
        val warning = WarningEntity(
            id = 1, createdAtEpochMs = 0, lastSeenAtEpochMs = 0, sessionId = 5,
            subsystem = Subsystem.FUELING.name, signal = Signal.O2_B1S1_V.name,
            level = "WATCH", kind = "BASELINE_DEVIATION",
            title = "${MetricId.O2_ACTIVITY_ONSET.displayName} above your car's usual",
            detail = "O2 activity onset is outside your car's usual range " +
                "(312.0 s vs typical 210.0 ± 40.0 s) — worth watching.",
            acknowledged = false, source = "REAL",
            dedupeKey = "BASELINE_DEVIATION:REAL:FUELING:O2_B1S1_V",
        )
        val result = cards(warnings = listOf(warning))
        val fueling = card(result, Subsystem.FUELING)
        assertEquals(HealthStatus.WATCH, fueling.status)
        assertEquals(warning.title, fueling.reason)
        // Only the title is checked here: it's what HealthRollup actually
        // reads into `reason` (the surfaced text). `warning.detail` above is
        // hand-authored fixture text HealthRollup.cards() never looks at, so
        // asserting on it would only catch a typo in this test, not a
        // production regression -- the real generated-detail heater-safety
        // check (against PostSessionEvaluator's actual output string) lives
        // in HealthViewModelTest's end-to-end version of this proof.
        assertFalse(fueling.reason.contains("heater", ignoreCase = true))
    }

    @Test
    fun baselineGate_rendersCollectingText() {
        val result = cards(baseline = mapOf(
            MetricId.MAF_WARM_IDLE to Baseline.Result.Gated(eligibleCount = 5, needed = 8),
        ))
        assertEquals("Collecting baseline · 5 of 8 drives",
            card(result, Subsystem.AIR_INTAKE).baselineGate)
        assertNull(card(result, Subsystem.COOLING).baselineGate)
    }

    @Test
    fun overall_isWorstOf_neverAnAverage() {
        val quiet = cards()
        assertEquals(HealthStatus.OK, HealthRollup.overall(quiet).status)
        assertEquals("All systems look normal", HealthRollup.overall(quiet).headline)

        val oneBad = cards(dtc = DtcReport(stored = listOf("P0171"), pending = emptyList()))
        val verdict = HealthRollup.overall(oneBad)
        assertEquals(HealthStatus.ATTENTION, verdict.status)
        assertEquals("Needs a look", verdict.headline)
        assertEquals("1 system needs attention — Fueling", verdict.subline)
    }
}
