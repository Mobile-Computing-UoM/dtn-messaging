package com.oppmesh.dtn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_logs")
data class ContactLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val localNodeId: String,
    val peerEndpointId: String,
    val peerNodeId: String?,
    val eventType: String,
    val statusCode: Int,
    val statusName: String,
    val timestampEpochMs: Long,
    val handshakeDurationMs: Long? = null,
    val contactDurationMs: Long? = null,
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val notes: String = ""
)
