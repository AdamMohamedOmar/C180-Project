package com.kompressorlink.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OdometerDao {
    @Insert
    suspend fun insert(entry: OdometerEntryEntity): Long

    @Query("SELECT * FROM odometer_entries ORDER BY epochMs DESC LIMIT 1")
    suspend fun latest(): OdometerEntryEntity?

    @Query("SELECT * FROM odometer_entries ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(): Flow<OdometerEntryEntity?>

    @Query("SELECT MAX(km) FROM odometer_entries")
    suspend fun maxKm(): Int?
}
