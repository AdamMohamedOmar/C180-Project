package com.kompressorlink.app.health

import com.kompressorlink.app.data.SessionRepository
import com.kompressorlink.app.data.SessionSource
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Turns the active TelemetrySource's streams into persisted session rows
// (spec §3). Close triggers: SESSION_CLOSE_GAP_MS without any event, a
// snapshot arriving after a gap, or a source switch. Persist gate:
// >= SESSION_MIN_DURATION_MS AND >= SESSION_MIN_SNAPSHOTS, else discarded.
// endedAt = last snapshot's receive time — a disconnect can't inflate
// duration. Time is injected (`now`) so tests run on virtual time.
class SessionRecorder(
    private val scope: CoroutineScope,
    private val source: TelemetrySource,
    private val choice: Flow<SourceChoice>,
    private val sessions: SessionRepository,
    private val refs: ReferenceRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSessionClosed: suspend (sessionId: Long, source: SessionSource) -> Unit = { _, _ -> },
) {
    private sealed interface Event {
        data class Snap(val snapshot: TelemetrySnapshot, val atMs: Long) : Event
        data class Dtc(val report: DtcReport?) : Event
        data class Switched(val to: SessionSource) : Event
    }

    private class OpenSession(val source: SessionSource, val startedAtMs: Long, isReal: Boolean, refs: ReferenceRepository) {
        val aggregator = SessionAggregator(refs, isReal)
        var lastAtMs: Long = startedAtMs
    }

    fun start(): Job = scope.launch {
        val events = Channel<Event>(Channel.BUFFERED)
        launch { source.telemetry.collect { events.send(Event.Snap(it, now())) } }
        launch { source.dtcReport.collect { events.send(Event.Dtc(it)) } }
        launch { choice.collect { events.send(Event.Switched(SessionSource.from(it))) } }

        var current: SessionSource? = null
        var open: OpenSession? = null

        suspend fun close() {
            val o = open ?: return
            open = null
            val duration = o.lastAtMs - o.startedAtMs
            if (duration < HealthTuning.SESSION_MIN_DURATION_MS ||
                o.aggregator.snapshotCount < HealthTuning.SESSION_MIN_SNAPSHOTS
            ) return  // blip — discarded, never pollutes history (spec §3)
            val id = sessions.record(
                SessionEntity(
                    startedAtEpochMs = o.startedAtMs,
                    endedAtEpochMs = o.lastAtMs,
                    source = o.source.name,
                    snapshotCount = o.aggregator.snapshotCount,
                    warmIdleSeconds = o.aggregator.warmIdleSeconds,
                    distanceKm = o.aggregator.distanceKm,
                    hasStoredDtc = o.aggregator.hasStoredDtc,
                )
            ) { sessionId -> o.aggregator.buildStats(sessionId) }
            onSessionClosed(id, o.source)
        }

        while (isActive) {
            val event = withTimeoutOrNull(HealthTuning.SESSION_CLOSE_GAP_MS) { events.receive() }
            when (event) {
                null -> close()  // nothing at all for the gap window
                is Event.Switched -> {
                    if (current != event.to) {
                        close()
                        current = event.to
                    }
                }
                is Event.Dtc -> open?.aggregator?.onDtcReport(event.report)
                is Event.Snap -> {
                    val src = current
                    if (src == null) {
                        // No SourceChoice emission seen yet — DataStore emits
                        // promptly on collect, so this is a startup race of a
                        // few ms at most; dropping the frame is harmless.
                        continue
                    }
                    val o = open
                    if (o != null && event.atMs - o.lastAtMs > HealthTuning.SESSION_CLOSE_GAP_MS) {
                        close()  // stale session: gap detected on arrival
                    }
                    val target = open ?: OpenSession(src, event.atMs, src.isReal, refs).also {
                        // Seed from the StateFlow's CURRENT value, not just future
                        // change events: a session can open after a stored DTC is
                        // already latched (app restart with the check-engine light
                        // already on, or a BLE gap splitting one drive into two
                        // sessions) — collect() alone would only replay/react at
                        // collector-start or on the next change, silently losing an
                        // already-present code otherwise (review fix).
                        it.aggregator.onDtcReport(source.dtcReport.value)
                        open = it
                    }
                    target.aggregator.add(event.snapshot, event.atMs)
                    target.lastAtMs = event.atMs
                }
            }
        }
    }
}
