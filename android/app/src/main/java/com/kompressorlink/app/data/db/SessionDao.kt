package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertStats(stats: List<SessionStatEntity>)

    // stats(id) is a factory so the FK is only known after the session insert
    // returns its rowid; @Transaction keeps session+stats atomic.
    @Transaction
    suspend fun insertSessionWithStats(
        session: SessionEntity,
        stats: (sessionId: Long) -> List<SessionStatEntity>,
    ): Long {
        val id = insertSession(session)
        insertStats(stats(id))
        return id
    }

    @Query("SELECT * FROM sessions WHERE source IN (:sources) ORDER BY endedAtEpochMs DESC LIMIT :limit")
    suspend fun recentBySources(sources: List<String>, limit: Int): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE source IN (:sources) ORDER BY endedAtEpochMs DESC LIMIT :limit")
    fun observeRecentBySources(sources: List<String>, limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session_stats WHERE sessionId IN (:sessionIds)")
    suspend fun statsForSessions(sessionIds: List<Long>): List<SessionStatEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE source IN (:sources)")
    suspend fun countBySources(sources: List<String>): Int

    @Query("SELECT SUM(distanceKm) FROM sessions WHERE source = 'REAL_BLE' AND endedAtEpochMs > :sinceEpochMs")
    suspend fun realDistanceSince(sinceEpochMs: Long): Float?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
