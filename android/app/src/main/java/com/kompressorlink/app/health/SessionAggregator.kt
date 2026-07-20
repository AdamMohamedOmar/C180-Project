package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.DashboardLogic
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot

// Streaming per-session aggregation (spec §3): Welford stats per signal,
// contextual means, RAW out-of-band seconds (pre-hysteresis — stats record
// what the car did; hysteresis smooths UI only), and REAL-only distance
// integration with the 5 s gap guard. Also accumulates the dynamic M271
// features (2026-07-17 enhancement plan): sustained high-load contextual
// means, cold-start warm-up rate, and O2 activity onset.
class SessionAggregator(
    private val refs: ReferenceRepository,
    private val isReal: Boolean,
) {
    private val overall = LinkedHashMap<Signal, StreamingStats>()
    private val warmIdle = HashMap<Signal, StreamingStats>()
    private val battRunning = StreamingStats()
    private val battOff = StreamingStats()
    private val outOfBandSec = HashMap<Signal, Float>()
    private val worst = HashMap<Signal, GaugeLevel>()

    private val highLoad = HashMap<Signal, StreamingStats>()
    private val highLoadGate = DwellGate(HealthTuning.HIGH_LOAD_DWELL_SNAPSHOTS)

    private enum class WarmupState { UNDECIDED, ACTIVE, DONE, NOT_COLD, ABANDONED }
    private var warmupState = WarmupState.UNDECIDED
    private val warmupSlope = StreamingSlope()
    private var warmupStartMs: Long? = null
    private var warmupEndMs: Long? = null
    private var coldStart = false
    private var sessionStartMs: Long? = null
    private val o2Onset = O2ActivityOnsetDetector()

    var snapshotCount = 0
        private set
    var warmIdleSeconds = 0f
        private set
    var hasStoredDtc = false
        private set

    private var distance = 0f
    private var speedSamples = 0
    private var prevAtMs: Long? = null
    private var prevSpeedAvailable = false

    fun onDtcReport(report: DtcReport?) {
        if (report != null && report.stored.isNotEmpty()) hasStoredDtc = true
    }

    /**
     * Called by a replay-style caller (RideReplayer) when it detects a data
     * gap it chose not to tick through. `prevAtMs` drives every dtSec-gated
     * accumulator (out-of-band seconds, warm-idle seconds) -- without this,
     * the first post-gap add() call would charge the FULL gap length to
     * whatever context that one fresh reading happens to land in (e.g. a
     * warm-idle-shaped resumption reading right after a parked stop).
     * Resetting it gives the post-gap call the same free dtMs=0 treatment a
     * brand-new SessionAggregator gets automatically -- which is what the
     * live path (SessionRecorder) already gets for free by constructing a
     * fresh aggregator after close().
     *
     * Also abandons an in-progress cold-start warm-up measurement: a gap
     * mid-warmup means we can no longer tell "still warming, just no data"
     * from "engine off and cooling" for the span it covers, so the
     * regression's x-axis (elapsed-minutes-since-session-start) can't be
     * trusted across it either. warmupRatePerMin stays null for the rest of
     * the session rather than silently reporting a rate a gap-spanning
     * outlier point skewed.
     */
    fun resetTimingAfterGap() {
        prevAtMs = null
        if (warmupState == WarmupState.ACTIVE) warmupState = WarmupState.ABANDONED
    }

    fun add(snapshot: TelemetrySnapshot, atMs: Long) {
        val dtMs = prevAtMs?.let { atMs - it } ?: 0L
        // Guard against a backward wall-clock jump (NTP correction, manual date
        // change) between snapshots: `now()` is wall-clock while the gap-close
        // timeout runs off a monotonic clock, so these can diverge (review note).
        val dtSec = maxOf(0f, dtMs / 1000f)
        snapshotCount++

        for (signal in Signal.entries) {
            val v = snapshot.value(signal) ?: continue
            overall.getOrPut(signal) { StreamingStats() }.add(v)
            val band = DashboardLogic.applicableBand(signal, snapshot, refs) ?: continue
            val level = DashboardLogic.levelFor(v, band)
            if (level != GaugeLevel.OK) {
                outOfBandSec[signal] = (outOfBandSec[signal] ?: 0f) + dtSec
            }
            if (rank(level) > rank(worst[signal] ?: GaugeLevel.OK)) worst[signal] = level
        }

        if (DashboardLogic.isWarmIdle(snapshot)) {
            warmIdleSeconds += dtSec
            for (signal in WARM_IDLE_SIGNALS) {
                snapshot.value(signal)?.let { warmIdle.getOrPut(signal) { StreamingStats() }.add(it) }
            }
        }

        when (SnapshotContexts.engineRunning(snapshot)) {
            true -> snapshot.value(Signal.BATT_V_ADC)?.let(battRunning::add)
            false -> snapshot.value(Signal.BATT_V_ADC)?.let(battOff::add)
            null -> Unit  // RPM unavailable: accumulate neither context
        }

        if (sessionStartMs == null) sessionStartMs = atMs
        val startMs = sessionStartMs!!
        // Same backward-wall-clock-jump guard as dtSec above (NTP correction,
        // manual date change): without it a negative elapsed-since-start
        // value could feed a bogus point into the warm-up slope regression
        // or break the O2 onset detector's monotonic-time assumption.
        val elapsedMs = maxOf(0L, atMs - startMs)

        // Cold-start warm-up slope (ECT vs minutes). Decided once, on the
        // FIRST ECT sample; slope only from a completed 40->80 °C climb.
        snapshot.value(Signal.ECT)?.let { ect ->
            if (warmupState == WarmupState.UNDECIDED) {
                coldStart = ect < HealthTuning.WARMUP_START_MAX_ECT
                warmupState = if (coldStart) WarmupState.ACTIVE else WarmupState.NOT_COLD
                if (coldStart) warmupStartMs = atMs
            }
            if (warmupState == WarmupState.ACTIVE) {
                warmupSlope.add(elapsedMs / 60_000f, ect)
                if (ect >= HealthTuning.WARMUP_COMPLETE_ECT) {
                    warmupState = WarmupState.DONE
                    warmupEndMs = atMs
                }
            }
        }

        // Sustained high load: debounced so a 0.5 s throttle blip never
        // counts as "load". Gate resets on false AND on unavailable.
        if (highLoadGate.update(SnapshotContexts.highLoad(snapshot))) {
            for (signal in HIGH_LOAD_SIGNALS) {
                snapshot.value(signal)?.let {
                    highLoad.getOrPut(signal) { StreamingStats() }.add(it)
                }
            }
        }

        // O2 activity onset — ingested unconditionally, NOT gated on
        // coldStart here. coldStart isn't decided until the first
        // ECT-bearing snapshot, but O2 (tier M, ~0.5 Hz) can and does arrive
        // before ECT (tier S, ~0.1 Hz) on every session — the scheduler
        // zero-initializes every signal's due-time at boot and breaks ties
        // by ascending table index, and O2_B1S1_V sits before ECT in that
        // table (firmware/src/pid_scheduler.cpp). Gating ingestion on
        // coldStart would silently drop those early samples on a session
        // that turns out to be cold. Exposure — not ingestion — is what's
        // gated on coldStart, in o2OnsetSOrNull below, so a warm session's
        // internal detector state is simply never surfaced.
        snapshot.value(Signal.O2_B1S1_V)?.let {
            o2Onset.add(elapsedMs / 1000f, it)
        }

        val speed = snapshot.value(Signal.SPEED)
        if (isReal && speed != null && prevSpeedAvailable &&
            dtMs in 1..HealthTuning.DISTANCE_MAX_GAP_MS
        ) {
            distance += speed * dtMs / 3_600_000f  // km/h * ms -> km
        }
        if (speed != null) speedSamples++
        prevSpeedAvailable = speed != null
        prevAtMs = atMs
    }

    /** Non-null only for real sessions that ever saw SPEED (spec §2/§3). */
    val distanceKm: Float?
        get() = if (isReal && speedSamples > 0) distance else null

    // °C/min, only for a COMPLETED cold-start warm-up spanning >= 2 min —
    // anything less is idle-vs-driving noise, not thermostat signal.
    private val warmupRatePerMin: Float?
        get() {
            if (warmupState != WarmupState.DONE) return null
            val start = warmupStartMs ?: return null
            val end = warmupEndMs ?: return null
            if (end - start < HealthTuning.WARMUP_MIN_SPAN_MS) return null
            return warmupSlope.slopePerX
        }

    private val o2OnsetSOrNull: Float?
        get() = o2Onset.onsetS?.takeIf { coldStart && it <= HealthTuning.O2_ONSET_MAX_S }

    fun buildStats(sessionId: Long): List<SessionStatEntity> =
        overall.map { (signal, stats) ->
            val wi = warmIdle[signal]
            val running = if (signal == Signal.BATT_V_ADC) battRunning else null
            val off = if (signal == Signal.BATT_V_ADC) battOff else null
            val hl = highLoad[signal]
            SessionStatEntity(
                sessionId = sessionId,
                signal = signal.name,
                sampleCount = stats.count,
                mean = stats.meanValue,
                min = stats.min,
                max = stats.max,
                stdDev = stats.stdDev,
                secondsOutOfBand = outOfBandSec[signal] ?: 0f,
                worstLevel = (worst[signal] ?: GaugeLevel.OK).name,
                warmIdleMean = wi?.takeIf { it.count > 0 }?.meanValue,
                warmIdleCount = wi?.count ?: 0,
                engineRunningMean = running?.takeIf { it.count > 0 }?.meanValue,
                engineRunningCount = running?.count ?: 0,
                engineOffMean = off?.takeIf { it.count > 0 }?.meanValue,
                engineOffCount = off?.count ?: 0,
                highLoadMean = hl?.takeIf { it.count > 0 }?.meanValue,
                highLoadCount = hl?.count ?: 0,
                warmupRatePerMin = if (signal == Signal.ECT) warmupRatePerMin else null,
                o2OnsetS = if (signal == Signal.O2_B1S1_V) o2OnsetSOrNull else null,
            )
        }

    private fun rank(level: GaugeLevel): Int = when (level) {
        GaugeLevel.RED -> 2
        GaugeLevel.AMBER -> 1
        else -> 0
    }

    companion object {
        // Signals with warm_idle contextual means in session_stats (spec §2).
        val WARM_IDLE_SIGNALS = setOf(Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT)

        // Signals with high_load contextual means in session_stats (2026-07-17
        // enhancement plan).
        val HIGH_LOAD_SIGNALS = setOf(Signal.LTFT1, Signal.MAF_GS)
    }
}
