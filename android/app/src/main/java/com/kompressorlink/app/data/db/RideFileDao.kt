package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RideFileDao {
    @Upsert
    suspend fun upsert(file: RideFileEntity)

    @Query("SELECT * FROM ride_files WHERE name = :name")
    suspend fun byName(name: String): RideFileEntity?

    @Query("SELECT * FROM ride_files ORDER BY name DESC")
    fun observeAll(): Flow<List<RideFileEntity>>

    @Query("SELECT * FROM ride_files ORDER BY name DESC")
    suspend fun all(): List<RideFileEntity>
}
