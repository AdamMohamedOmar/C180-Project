package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.KlDatabase
import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.OdometerEntryEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.maintenance.BuiltinSchedule
import kotlinx.coroutines.flow.Flow

class RoomSessionRepository(private val db: KlDatabase) : SessionRepository {
    override suspend fun record(
        session: SessionEntity,
        stats: (sessionId: Long) -> List<SessionStatEntity>,
    ): Long = db.sessionDao().insertSessionWithStats(session, stats)

    override suspend fun recent(sources: Set<SessionSource>, limit: Int): List<SessionEntity> =
        db.sessionDao().recentBySources(sources.map { it.name }, limit)

    override fun observeRecent(sources: Set<SessionSource>, limit: Int): Flow<List<SessionEntity>> =
        db.sessionDao().observeRecentBySources(sources.map { it.name }, limit)

    override suspend fun statsForSessions(sessionIds: List<Long>): List<SessionStatEntity> =
        if (sessionIds.isEmpty()) emptyList() else db.sessionDao().statsForSessions(sessionIds)

    override suspend fun count(sources: Set<SessionSource>): Int =
        db.sessionDao().countBySources(sources.map { it.name })

    override suspend fun realDistanceSince(epochMs: Long): Float =
        db.sessionDao().realDistanceSince(epochMs) ?: 0f
}

class RoomWarningRepository(private val db: KlDatabase) : WarningRepository {
    override suspend fun raise(candidate: WarningEntity) {
        val open = db.warningDao().openByKey(candidate.dedupeKey)
        if (open == null) {
            db.warningDao().insert(candidate)
        } else {
            db.warningDao().update(
                open.copy(
                    lastSeenAtEpochMs = candidate.lastSeenAtEpochMs,
                    detail = candidate.detail,
                    level = candidate.level,
                )
            )
        }
    }

    override fun observe(source: WarningSource, limit: Int): Flow<List<WarningEntity>> =
        db.warningDao().observeBySource(source.name, limit)

    override suspend fun open(source: WarningSource): List<WarningEntity> =
        db.warningDao().openBySource(source.name)

    override suspend fun acknowledge(id: Long) = db.warningDao().acknowledge(id)
}

class RoomOdometerRepository(private val db: KlDatabase) : OdometerRepository {
    override suspend fun addAnchor(km: Int, atEpochMs: Long): Result<Unit> {
        val maxSoFar = db.odometerDao().maxKm()
        if (maxSoFar != null && km < maxSoFar) {
            return Result.failure(
                IllegalArgumentException(
                    "Odometer can't go backwards: last entered $maxSoFar km"
                )
            )
        }
        db.odometerDao().insert(OdometerEntryEntity(epochMs = atEpochMs, km = km))
        return Result.success(Unit)
    }

    override suspend fun latestAnchor(): OdometerEntryEntity? = db.odometerDao().latest()

    override fun observeLatestAnchor(): Flow<OdometerEntryEntity?> = db.odometerDao().observeLatest()
}

class RoomMaintenanceRepository(private val db: KlDatabase) : MaintenanceRepository {
    override suspend fun ensureSeeded() {
        if (db.maintenanceDao().builtinCount() == 0) {
            db.maintenanceDao().insertItems(BuiltinSchedule.ITEMS)
        }
    }

    override fun observeItems(): Flow<List<MaintenanceItemEntity>> = db.maintenanceDao().observeItems()
    override suspend fun items(): List<MaintenanceItemEntity> = db.maintenanceDao().items()
    override suspend fun itemById(id: Long): MaintenanceItemEntity? = db.maintenanceDao().itemById(id)
    override suspend fun latestLogFor(itemId: Long): ServiceLogEntity? = db.maintenanceDao().latestLogFor(itemId)
    override fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>> = db.maintenanceDao().observeLogsFor(itemId)

    override suspend fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?) {
        db.maintenanceDao().insertLog(ServiceLogEntity(itemId = itemId, epochMs = epochMs, km = km, note = note))
        db.maintenanceDao().clearNotified(itemId)
    }

    private fun validate(item: MaintenanceItemEntity): Result<Unit> =
        if (item.intervalKm == null && item.intervalMonths == null) {
            Result.failure(IllegalArgumentException("Set a km interval, a month interval, or both"))
        } else Result.success(Unit)

    override suspend fun updateItem(item: MaintenanceItemEntity): Result<Unit> =
        validate(item).onSuccess { db.maintenanceDao().updateItem(item) }

    override suspend fun addCustomItem(item: MaintenanceItemEntity): Result<Long> {
        validate(item).onFailure { return Result.failure(it) }
        return Result.success(db.maintenanceDao().insertItem(item.copy(builtin = false)))
    }

    override suspend fun deleteCustomItem(id: Long) { db.maintenanceDao().deleteCustomItem(id) }
    override suspend fun stampNotified(id: Long, atEpochMs: Long) = db.maintenanceDao().stampNotified(id, atEpochMs)
}
