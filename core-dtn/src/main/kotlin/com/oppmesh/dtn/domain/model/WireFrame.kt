package com.oppmesh.dtn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class FrameType {
    HELLO_SUMMARY,
    PACKET_BUNDLE,
    DELIVERY_ACK
}

@Serializable
data class NetworkPacketDto(
    val packetId: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val payloadText: String,
    val strategy: RoutingStrategy,
    val sprayCopiesToTransfer: Int,
    val hopCount: Int,
    val hopPathCsv: String,
    val createdAtEpochMs: Long,
    val ttlMillis: Long,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val filePayloadId: Long? = null
)

@Serializable
data class WireFrame(
    val frameType: FrameType,
    val senderNodeId: String,
    val senderWallClockMs: Long,
    val knownPacketIds: List<String> = emptyList(),
    val deliveredPacketIds: List<String> = emptyList(),
    val packets: List<NetworkPacketDto> = emptyList()
)
