package com.oppmesh.dtn.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacket(packet: PacketEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPackets(packets: List<PacketEntity>)

    @Update
    suspend fun updatePacket(packet: PacketEntity)

    @Query("SELECT * FROM packets ORDER BY createdAtEpochMs DESC")
    fun getAllPacketsFlow(): Flow<List<PacketEntity>>

    @Query("SELECT * FROM packets ORDER BY createdAtEpochMs DESC")
    suspend fun getAllPacketsSync(): List<PacketEntity>

    @Query("SELECT * FROM packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getPacketById(packetId: String): PacketEntity?

    @Query("SELECT * FROM packets WHERE status != 'EXPIRED'")
    suspend fun getActivePackets(): List<PacketEntity>

    @Query("UPDATE packets SET status = 'DELIVERED', deliveredAtEpochMs = :deliveredAt WHERE packetId = :packetId")
    suspend fun markDelivered(packetId: String, deliveredAt: Long)

    @Query("UPDATE packets SET status = 'DELIVERED', deliveredAtEpochMs = :deliveredAt WHERE packetId IN (:packetIds)")
    suspend fun markDeliveredBatch(packetIds: List<String>, deliveredAt: Long)

    @Query("UPDATE packets SET sprayCopiesRemaining = :copiesRemaining WHERE packetId = :packetId")
    suspend fun updateCopiesRemaining(packetId: String, copiesRemaining: Int)

    @Query("UPDATE packets SET transmissionCount = transmissionCount + 1 WHERE packetId = :packetId")
    suspend fun incrementTransmission(packetId: String)

    @Query("UPDATE packets SET status = 'CARRIED' WHERE packetId = :packetId AND status = 'STORED'")
    suspend fun markCarried(packetId: String)

    @Query("UPDATE packets SET filePath = :filePath WHERE packetId = :packetId")
    suspend fun updateFilePath(packetId: String, filePath: String)

    @Query("DELETE FROM packets")
    suspend fun clearAll()
}
