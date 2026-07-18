package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WarningDao {
    @Insert
    suspend fun insert(warning: WarningEntity): Long

    @Update
    suspend fun update(warning: WarningEntity)

    @Query("SELECT * FROM warnings WHERE dedupeKey = :key AND acknowledged = 0 LIMIT 1")
    suspend fun openByKey(key: String): WarningEntity?

    @Query("UPDATE warnings SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    // Feed order (spec §8.3): unacknowledged first, then newest.
    @Query("SELECT * FROM warnings WHERE source = :source ORDER BY acknowledged ASC, lastSeenAtEpochMs DESC LIMIT :limit")
    fun observeBySource(source: String, limit: Int): Flow<List<WarningEntity>>

    @Query("SELECT * FROM warnings WHERE source = :source AND acknowledged = 0")
    suspend fun openBySource(source: String): List<WarningEntity>
}
