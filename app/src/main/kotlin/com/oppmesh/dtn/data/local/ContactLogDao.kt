package com.oppmesh.dtn.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ContactLogEntity): Long

    @Update
    suspend fun updateLog(log: ContactLogEntity)

    @Query("SELECT * FROM contact_logs ORDER BY timestampEpochMs DESC")
    fun getAllLogsFlow(): Flow<List<ContactLogEntity>>

    @Query("SELECT * FROM contact_logs ORDER BY timestampEpochMs DESC")
    suspend fun getAllLogsSync(): List<ContactLogEntity>

    @Query("SELECT * FROM contact_logs ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int): List<ContactLogEntity>

    @Query("DELETE FROM contact_logs")
    suspend fun clearAll()
}
