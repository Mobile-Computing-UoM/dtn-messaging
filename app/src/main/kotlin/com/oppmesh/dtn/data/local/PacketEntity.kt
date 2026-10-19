package com.oppmesh.dtn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.oppmesh.dtn.domain.model.RoutingStrategy
import com.oppmesh.dtn.domain.routing.DtnPacketHolder

@Entity(tableName = "packets")
data class PacketEntity(
    @PrimaryKey override val packetId: String,
    override val sourceNodeId: String,
    override val destinationNodeId: String,
    override val payloadText: String,
    override val strategy: RoutingStrategy,
    override val sprayCopiesRemaining: Int,
    override val hopCount: Int,
    override val hopPathCsv: String,
    override val createdAtEpochMs: Long,
    val storedAtLocalEpochMs: Long,
    val deliveredAtEpochMs: Long? = null,
    override val ttlMillis: Long = 3_600_000L,
    val transmissionCount: Int = 0,
    val status: String, // "STORED", "CARRIED", "DELIVERED", "EXPIRED"
    override val fileName: String? = null,
    override val fileSizeBytes: Long? = null,
    override val filePath: String? = null
) : DtnPacketHolder {
    override val isDelivered: Boolean
        get() = status == "DELIVERED" || deliveredAtEpochMs != null
}
