package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.DashboardLogic
import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.WarningRepository
import com.kompressorlink.app.data.WarningSource
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySource
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// App-scoped: owns the post-hysteresis level truth every screen shares
// (spec §5.5) and raises LIVE_OUT_OF_BAND + DTC warnings through the
// deduping WarningRepository. RED raises immediately (post-hysteresis);
// AMBER raises after a sustained dwell. All warnings carry the active
// source's SIM/REAL tag — the quarantine holds in the warnings table too.
class LiveWarningMonitor(
    private val scope: CoroutineScope,
    private val source: TelemetrySource,
    private val choice: Flow<SourceChoice>,
    private val refs: ReferenceRepository,
    private val warnings: WarningRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _levels = MutableStateFlow<Map<Signal, GaugeLevel>>(emptyMap())
    val levels: StateFlow<Map<Signal, GaugeLevel>> = _levels

    fun start(): Job = scope.launch {
        val filters = Signal.entries.associateWith { HysteresisFilter() }
        val amberSince = HashMap<Signal, Long>()
        // AtomicReference, not a plain `var`: this parent coroutine's scope is
        // Dispatchers.Default in production (a real thread pool), and
        // warningSource is written by the `choice` collector below while
        // being read by the `telemetry` and `dtcReport` collectors — three
        // independently-launched sibling coroutines that may run on
        // different threads with no channel or other synchronization
        // between them. A plain var here would be an unsynchronized
        // cross-thread data race, and — unlike a dropped telemetry frame —
        // a torn/stale read here can permanently mistag a warning's SIM/REAL
        // source, since WarningRepository.raise()'s dedupe-upsert never
        // corrects `source` on a later re-raise of the same dedupeKey.
        //
        // Priming matters just as much as the atomicity: `choice` (a cold,
        // DataStore-backed Flow in production) is collected below, but its
        // FIRST emission is not synchronous the way a MutableStateFlow's is
        // in tests — telemetry/dtcReport could observe an out-of-band value
        // on their very first frame (HysteresisFilter bypasses smoothing on
        // a signal's first frame) before `choice.collect` below has emitted
        // anything, permanently mis-tagging a genuine REAL warning as SIM.
        // So `choice.first()` is awaited HERE, before any child coroutine is
        // launched — this is a suspend point in the parent, so nothing below
        // starts until priming completes, closing the window deterministically
        // rather than just bounding it to a few ms the way SessionRecorder's
        // accepted dropped-frame race does.
        val warningSourceRef = AtomicReference(
            WarningSource.from(SessionSource.from(choice.first()))
        )
        var prevStored = emptySet<String>()
        var prevPending = emptySet<String>()

        launch {
            choice.collect { warningSourceRef.set(WarningSource.from(SessionSource.from(it))) }
        }

        launch {
            source.telemetry.collect { snapshot ->
                val atMs = now()
                val newLevels = HashMap<Signal, GaugeLevel>()
                for (signal in DashboardLogic.DASHBOARD_SIGNALS) {
                    val value = snapshot.value(signal)
                    val band = DashboardLogic.applicableBand(signal, snapshot, refs)
                    val raw = when {
                        value == null -> GaugeLevel.UNAVAILABLE
                        band == null -> GaugeLevel.NEUTRAL
                        else -> DashboardLogic.levelFor(value, band)
                    }
                    val level = filters.getValue(signal).update(raw, atMs)
                    newLevels[signal] = level

                    val subsystem = Subsystem.SIGNAL_SUBSYSTEMS[signal]
                    if (subsystem != null && value != null && band != null) {
                        when (level) {
                            GaugeLevel.RED -> {
                                amberSince.remove(signal)
                                warnings.raise(liveWarning(
                                    signal, subsystem, "ATTENTION",
                                    title = "${title(signal)} out of range",
                                    value = value, band = band, atMs = atMs,
                                    source = warningSourceRef.get(),
                                ))
                            }
                            GaugeLevel.AMBER -> {
                                val since = amberSince.getOrPut(signal) { atMs }
                                if (atMs - since >= HealthTuning.AMBER_WARN_DWELL_MS) {
                                    warnings.raise(liveWarning(
                                        signal, subsystem, "WATCH",
                                        title = "${title(signal)} outside its band",
                                        value = value, band = band, atMs = atMs,
                                        source = warningSourceRef.get(),
                                    ))
                                }
                            }
                            else -> amberSince.remove(signal)
                        }
                    }
                }
                _levels.value = newLevels
            }
        }

        launch {
            source.dtcReport.collect { report ->
                val atMs = now()
                val stored = report?.stored?.toSet() ?: emptySet()
                val pending = report?.pending?.toSet() ?: emptySet()
                (stored - prevStored).forEach { code ->
                    warnings.raise(dtcWarning(code, "ATTENTION",
                        "Stored code $code",
                        "The ECU has confirmed and stored $code. See the DTCs tab for what it means and what to check first.",
                        atMs, warningSourceRef.get()))
                }
                (pending - prevPending).forEach { code ->
                    warnings.raise(dtcWarning(code, "WATCH",
                        "Pending code $code",
                        "The ECU has seen $code but not confirmed it yet. If it recurs it becomes a stored code.",
                        atMs, warningSourceRef.get()))
                }
                prevStored = stored
                prevPending = pending
            }
        }
    }

    private fun title(signal: Signal): String = when (signal) {
        Signal.MAF_GS -> "MAF"; Signal.STFT1 -> "STFT"; Signal.LTFT1 -> "LTFT"
        Signal.ECT -> "Coolant"; Signal.BATT_V_ADC -> "Battery"
        Signal.O2_B1S1_V -> "O2 pre-cat"; Signal.O2_B1S2_V -> "O2 post-cat"
        else -> signal.name
    }

    private fun liveWarning(
        signal: Signal, subsystem: Subsystem, level: String, title: String,
        value: Float, band: com.kompressorlink.app.reference.Band,
        atMs: Long, source: WarningSource,
    ) = WarningEntity(
        createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = null,
        subsystem = subsystem.name, signal = signal.name, level = level,
        kind = "LIVE_OUT_OF_BAND", title = title,
        detail = String.format(Locale.US, "%.1f %s vs healthy %.1f–%.1f %s. %s",
            value, band.unit, band.lo, band.hi, band.unit, band.hint),
        acknowledged = false, source = source.name,
        dedupeKey = "LIVE_OUT_OF_BAND:${source.name}:${subsystem.name}:${signal.name}",
    )

    private fun dtcWarning(
        code: String, level: String, title: String, detail: String,
        atMs: Long, source: WarningSource,
    ) = WarningEntity(
        createdAtEpochMs = atMs, lastSeenAtEpochMs = atMs, sessionId = null,
        subsystem = DtcSubsystemMap.subsystemFor(code).name, signal = null,
        level = level, kind = "DTC", title = title, detail = detail,
        acknowledged = false, source = source.name,
        dedupeKey = "DTC:${source.name}:${DtcSubsystemMap.subsystemFor(code).name}:$code",
    )
}
