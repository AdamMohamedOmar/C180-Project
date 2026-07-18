package com.kompressorlink.app.data

import com.kompressorlink.app.data.db.MaintenanceItemEntity
import com.kompressorlink.app.data.db.OdometerEntryEntity
import com.kompressorlink.app.data.db.ServiceLogEntity
import com.kompressorlink.app.data.db.SessionEntity
import com.kompressorlink.app.data.db.SessionStatEntity
import com.kompressorlink.app.data.db.WarningEntity
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun record(session: SessionEntity, stats: (sessionId: Long) -> List<SessionStatEntity>): Long
    suspend fun recent(sources: Set<SessionSource>, limit: Int): List<SessionEntity>
    fun observeRecent(sources: Set<SessionSource>, limit: Int): Flow<List<SessionEntity>>
    suspend fun statsForSessions(sessionIds: List<Long>): List<SessionStatEntity>
    suspend fun count(sources: Set<SessionSource>): Int
    /** Sum of REAL_BLE session distances ended after the given time; 0 if none. */
    suspend fun realDistanceSince(epochMs: Long): Float
}

interface WarningRepository {
    /**
     * Dedupe upsert (spec §2): if an unacknowledged warning with the same
     * dedupeKey exists, update lastSeenAt + detail + level; else insert.
     */
    suspend fun raise(candidate: WarningEntity)
    fun observe(source: WarningSource, limit: Int = 50): Flow<List<WarningEntity>>
    suspend fun open(source: WarningSource): List<WarningEntity>
    suspend fun acknowledge(id: Long)
}

interface OdometerRepository {
    /**
     * Odometers don't run backwards: rejects km below the highest existing
     * anchor with a human-readable message in the failure.
     */
    suspend fun addAnchor(km: Int, atEpochMs: Long): Result<Unit>
    suspend fun latestAnchor(): OdometerEntryEntity?
    fun observeLatestAnchor(): Flow<OdometerEntryEntity?>
}

interface MaintenanceRepository {
    /**
     * Inserts BuiltinSchedule.ITEMS exactly once (idempotent).
     * Called fire-and-forget at app startup — observeItems()/items() may
     * transiently see an empty list before seeding completes; a Flow-based
     * observer will get a fresh emission once the insert commits.
     */
    suspend fun ensureSeeded()
    fun observeItems(): Flow<List<MaintenanceItemEntity>>
    suspend fun items(): List<MaintenanceItemEntity>
    suspend fun itemById(id: Long): MaintenanceItemEntity?
    suspend fun latestLogFor(itemId: Long): ServiceLogEntity?
    fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>>
    /** Also clears the item's lastNotifiedAtEpochMs (spec §6.3). */
    suspend fun logService(itemId: Long, epochMs: Long, km: Int?, note: String?)
    /** Enforces: at least one of intervalKm/intervalMonths non-null. */
    suspend fun updateItem(item: MaintenanceItemEntity): Result<Unit>
    suspend fun addCustomItem(item: MaintenanceItemEntity): Result<Long>
    suspend fun deleteCustomItem(id: Long)
    suspend fun stampNotified(id: Long, atEpochMs: Long)
}
