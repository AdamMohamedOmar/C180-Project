package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MaintenanceDao {
    @Insert
    suspend fun insertItem(item: MaintenanceItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<MaintenanceItemEntity>)

    @Update
    suspend fun updateItem(item: MaintenanceItemEntity)

    // builtin = 0 guard: builtins are never deletable (spec §2). Returns
    // rows-affected so callers can detect the guard tripping (0 = refused)
    // in one round-trip instead of a follow-up itemById() query.
    @Query("DELETE FROM maintenance_items WHERE id = :id AND builtin = 0")
    suspend fun deleteCustomItem(id: Long): Int

    @Query("SELECT * FROM maintenance_items ORDER BY name")
    fun observeItems(): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT * FROM maintenance_items")
    suspend fun items(): List<MaintenanceItemEntity>

    @Query("SELECT * FROM maintenance_items WHERE id = :id")
    suspend fun itemById(id: Long): MaintenanceItemEntity?

    @Query("SELECT COUNT(*) FROM maintenance_items WHERE builtin = 1")
    suspend fun builtinCount(): Int

    @Query("UPDATE maintenance_items SET lastNotifiedAtEpochMs = :atMs WHERE id = :id")
    suspend fun stampNotified(id: Long, atMs: Long)

    @Query("UPDATE maintenance_items SET lastNotifiedAtEpochMs = NULL WHERE id = :id")
    suspend fun clearNotified(id: Long)

    @Insert
    suspend fun insertLog(log: ServiceLogEntity): Long

    @Query("SELECT * FROM service_log WHERE itemId = :itemId ORDER BY epochMs DESC")
    fun observeLogsFor(itemId: Long): Flow<List<ServiceLogEntity>>

    @Query("SELECT * FROM service_log WHERE itemId = :itemId ORDER BY epochMs DESC LIMIT 1")
    suspend fun latestLogFor(itemId: Long): ServiceLogEntity?

    @Query("SELECT * FROM service_log ORDER BY epochMs DESC")
    suspend fun allLogs(): List<ServiceLogEntity>
}
