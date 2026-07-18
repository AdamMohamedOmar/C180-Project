package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.OdometerEntryEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FakeSessionRepository : SessionRepository {
    val sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val stats = MutableStateFlow<List<SessionStatEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun record(
        session: SessionEntity,
        stats: (sessionId: Long) -> List<SessionStatEntity>,
    ): Long {
        val id = nextId++
        sessions.value = sessions.value + session.copy(id = id)
        this.stats.value = this.stats.value + stats(id)
        return id
    }

    private fun filtered(sources: Set<SessionSource>, limit: Int) =
        sessions.value.filter { s -> sources.any { it.name == s.source } }
            .sortedByDescending { it.endedAtEpochMs }.take(limit)

    override suspend fun recent(sources: Set<SessionSource>, limit: Int) = filtered(sources, limit)

    override fun observeRecent(sources: Set<SessionSource>, limit: Int): Flow<List<SessionEntity>> =
        sessions.map { list ->
            list.filter { s -> sources.any { it.name == s.source } }
                .sortedByDescending { it.endedAtEpochMs }.take(limit)
        }

    override suspend fun statsForSessions(sessionIds: List<Long>) =
        stats.value.filter { it.sessionId in sessionIds }

    override suspend fun count(sources: Set<SessionSource>) = filtered(sources, Int.MAX_VALUE).size

    override suspend fun realDistanceSince(epochMs: Long): Float =
        sessions.value.filter { it.source == SessionSource.REAL_BLE.name && it.endedAtEpochMs > epochMs }
            .mapNotNull { it.distanceKm }.sum()
}

class FakeWarningRepository : WarningRepository {
    val warnings = MutableStateFlow<List<WarningEntity>>(emptyList())
    private var nextId = 1L

    // Same class of fidelity gap as FakeMaintenanceRepository's writeRevision
    // (see its comment): the real WarningDao backing raise()'s dedupe-upsert
    // executes a genuine INSERT or UPDATE statement every time, and Room's
    // InvalidationTracker re-fires observeBySource()'s query on any such
    // statement -- regardless of whether the row's column values end up
    // identical to before (e.g. acknowledge() called twice on the same
    // already-acknowledged id, or raise()'s update-branch landing the exact
    // same detail/level it already had). MutableStateFlow conflates
    // consecutive structurally-equal lists, so without this counter such a
    // no-visible-change write would silently fail to re-emit here even
    // though real Room would. No currently-passing test depends on the
    // conflating behavior (verified: nothing else drives .observe() through
    // a duplicate-content write), so this only tightens fidelity.
    private val writeRevision = MutableStateFlow(0)

    override suspend fun raise(candidate: WarningEntity) {
        val open = warnings.value.firstOrNull { !it.acknowledged && it.dedupeKey == candidate.dedupeKey }
        warnings.value = if (open == null) {
            warnings.value + candidate.copy(id = nextId++)
        } else {
            warnings.value.map {
                if (it.id == open.id) it.copy(
                    lastSeenAtEpochMs = candidate.lastSeenAtEpochMs,
                    detail = candidate.detail, level = candidate.level,
                ) else it
            }
        }
        writeRevision.value++
    }

    override fun observe(source: WarningSource, limit: Int): Flow<List<WarningEntity>> =
        combine(warnings, writeRevision) { list, _ -> list }.map { list ->
            list.filter { it.source == source.name }
                .sortedWith(compareBy<WarningEntity> { it.acknowledged }.thenByDescending { it.lastSeenAtEpochMs })
                .take(limit)
        }

    override suspend fun open(source: WarningSource) =
        warnings.value.filter { it.source == source.name && !it.acknowledged }

    override suspend fun acknowledge(id: Long) {
        if (warnings.value.any { it.id == id }) {
            warnings.value = warnings.value.map { if (it.id == id) it.copy(acknowledged = true) else it }
            writeRevision.value++
        }
    }
}

class FakeOdometerRepository : OdometerRepository {
    val anchors = MutableStateFlow<List<OdometerEntryEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun addAnchor(km: Int, atEpochMs: Long): Result<Unit> {
        val maxSoFar = anchors.value.maxOfOrNull { it.km }
        if (maxSoFar != null && km < maxSoFar) {
            return Result.failure(IllegalArgumentException("Odometer can't go backwards: last entered $maxSoFar km"))
        }
        anchors.value = anchors.value + OdometerEntryEntity(id = nextId++, epochMs = atEpochMs, km = km)
        return Result.success(Unit)
    }

    override suspend fun latestAnchor() = anchors.value.maxByOrNull { it.epochMs }

    override fun observeLatestAnchor(): Flow<OdometerEntryEntity?> =
        anchors.map { list -> list.maxByOrNull { it.epochMs } }
}

class FakeMaintenanceRepository : MaintenanceRepository {
    val items = MutableStateFlow<List<MaintenanceItemEntity>>(emptyList())
    val logs = MutableStateFlow<List<ServiceLogEntity>>(emptyList())
    private var nextItemId = 1L
    private var nextLogId = 1L

    // Room's InvalidationTracker re-runs observeItems()'s query whenever an
    // INSERT/UPDATE/DELETE actually executes against maintenance_items --
    // regardless of whether the resulting column values differ from before
    // (e.g. clearNotified() setting an already-null lastNotifiedAtEpochMs to
    // NULL still re-invalidates the table in real Room). MutableStateFlow,
    // by contrast, conflates consecutive equal values and would silently
    // drop that re-emission. Every write that touches a row bumps this
    // counter so observeItems() re-fires the same way Room's would, even
    // when the item list content is unchanged.
    private val writeRevision = MutableStateFlow(0)

    fun seedWith(list: List<MaintenanceItemEntity>) {
        items.value = list.map { it.copy(id = nextItemId++) }
    }

    override suspend fun ensureSeeded() {
        // Additive, not a reset: mirrors Room's ensureSeeded (guarded
        // insertItems, never touches existing rows). Must NOT route through
        // seedWith() here — that helper REPLACES items.value wholesale, which
        // would silently drop any custom item already added before this runs
        // (and orphan its logged service_log rows, with no cascade-equivalent
        // in this in-memory fake).
        if (items.value.none { it.builtin }) {
            items.value = items.value + com.kompressorlink.app.maintenance.BuiltinSchedule.ITEMS.map { it.copy(id = nextItemId++) }
        }
    }

    override fun observeItems(): Flow<List<MaintenanceItemEntity>> =
        combine(items, writeRevision) { list, _ -> list }
    override suspend fun items() = items.value
    override suspend fun itemById(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun latestLogFor(itemId: Long) =
        logs.value.filter { it.itemId == itemId }.maxByOrNull { it.epochMs }

    override fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>> =
        logs.map { list -> list.filter { it.itemId == itemId }.sortedByDescending { it.epochMs } }

    override suspend fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?) {
        logs.value = logs.value + ServiceLogEntity(id = nextLogId++, itemId = itemId, epochMs = epochMs, km = km, note = note)
        items.value = items.value.map { if (it.id == itemId) it.copy(lastNotifiedAtEpochMs = null) else it }
        writeRevision.value++
    }

    override suspend fun updateItem(item: MaintenanceItemEntity): Result<Unit> {
        if (item.intervalKm == null && item.intervalMonths == null) {
            return Result.failure(IllegalArgumentException("Set a km interval, a month interval, or both"))
        }
        items.value = items.value.map { if (it.id == item.id) item else it }
        writeRevision.value++
        return Result.success(Unit)
    }

    override suspend fun addCustomItem(item: MaintenanceItemEntity): Result<Long> {
        if (item.intervalKm == null && item.intervalMonths == null) {
            return Result.failure(IllegalArgumentException("Set a km interval, a month interval, or both"))
        }
        val id = nextItemId++
        items.value = items.value + item.copy(id = id, builtin = false)
        writeRevision.value++
        return Result.success(id)
    }

    // Guard mirrors Room: MaintenanceDao.deleteCustomItem is
    // `DELETE ... WHERE id = :id AND builtin = 0`, so the service_log FK's
    // ON DELETE CASCADE only fires when that delete actually removes a row.
    // Attempting to delete a builtin item's id must therefore be a true
    // no-op here too — item AND its logs both stay. Do not clear logs
    // unconditionally; that would silently diverge from Room (a past bug).
    override suspend fun deleteCustomItem(id: Long) {
        val existing = items.value.firstOrNull { it.id == id && !it.builtin }
        if (existing != null) {
            items.value = items.value.filterNot { it.id == id }
            logs.value = logs.value.filterNot { it.itemId == id }
            writeRevision.value++
        }
    }

    override suspend fun stampNotified(id: Long, atEpochMs: Long) {
        if (items.value.any { it.id == id }) {
            items.value = items.value.map { if (it.id == id) it.copy(lastNotifiedAtEpochMs = atEpochMs) else it }
            writeRevision.value++
        }
    }
}
