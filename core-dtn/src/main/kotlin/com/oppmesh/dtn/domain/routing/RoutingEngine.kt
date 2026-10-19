package com.oppmesh.dtn.domain.routing

import com.oppmesh.dtn.domain.model.NetworkPacketDto
import com.oppmesh.dtn.domain.model.RoutingStrategy

interface DtnPacketHolder {
    val packetId: String
    val sourceNodeId: String
    val destinationNodeId: String
    val payloadText: String
    val strategy: RoutingStrategy
    val sprayCopiesRemaining: Int
    val hopCount: Int
    val hopPathCsv: String
    val createdAtEpochMs: Long
    val ttlMillis: Long
    val isDelivered: Boolean
    val fileName: String? get() = null
    val fileSizeBytes: Long? get() = null
    val filePath: String? get() = null
}

data class SimplePacket(
    override val packetId: String,
    override val sourceNodeId: String,
    override val destinationNodeId: String,
    override val payloadText: String,
    override val strategy: RoutingStrategy,
    override val sprayCopiesRemaining: Int,
    override val hopCount: Int = 0,
    override val hopPathCsv: String = sourceNodeId,
    override val createdAtEpochMs: Long,
    override val ttlMillis: Long = 3_600_000L,
    override val isDelivered: Boolean = false,
    override val fileName: String? = null,
    override val fileSizeBytes: Long? = null,
    override val filePath: String? = null
) : DtnPacketHolder

data class OutgoingPacketSelection(
    val packetDto: NetworkPacketDto,
    val newLocalCopiesRemaining: Int
)

object RoutingEngine {

    private fun toDto(packet: DtnPacketHolder, copiesToTransfer: Int): NetworkPacketDto {
        return NetworkPacketDto(
            packetId = packet.packetId,
            sourceNodeId = packet.sourceNodeId,
            destinationNodeId = packet.destinationNodeId,
            payloadText = packet.payloadText,
            strategy = packet.strategy,
            sprayCopiesToTransfer = copiesToTransfer,
            hopCount = packet.hopCount,
            hopPathCsv = packet.hopPathCsv,
            createdAtEpochMs = packet.createdAtEpochMs,
            ttlMillis = packet.ttlMillis,
            fileName = packet.fileName,
            fileSizeBytes = packet.fileSizeBytes
        )
    }

    fun selectPacketsForPeer(
        localPackets: Collection<DtnPacketHolder>,
        peerNodeId: String,
        peerKnownPacketIds: Set<String>,
        peerDeliveredPacketIds: Set<String>,
        currentEpochMs: Long
    ): List<OutgoingPacketSelection> {
        val outgoing = mutableListOf<OutgoingPacketSelection>()

        val knownNormalized = peerKnownPacketIds.map { it.trim().lowercase() }.toSet()
        val deliveredNormalized = peerDeliveredPacketIds.map { it.trim().lowercase() }.toSet()

        for (packet in localPackets) {
            // Expire old packets
            if (currentEpochMs - packet.createdAtEpochMs > packet.ttlMillis) {
                continue
            }
            // Skip already delivered locally or known/delivered by peer
            val pId = packet.packetId.trim().lowercase()
            if (packet.isDelivered || pId in deliveredNormalized || pId in knownNormalized) {
                continue
            }

            val isDestination = packet.destinationNodeId.trim().equals(peerNodeId.trim(), ignoreCase = true)

            when (packet.strategy) {
                RoutingStrategy.DIRECT_DELIVERY -> {
                    if (isDestination) {
                        outgoing.add(
                            OutgoingPacketSelection(
                                packetDto = toDto(packet, 1),
                                newLocalCopiesRemaining = packet.sprayCopiesRemaining
                            )
                        )
                    }
                }
                RoutingStrategy.EPIDEMIC -> {
                    outgoing.add(
                        OutgoingPacketSelection(
                            packetDto = toDto(packet, 1),
                            newLocalCopiesRemaining = packet.sprayCopiesRemaining
                        )
                    )
                }
                RoutingStrategy.SPRAY_AND_WAIT -> {
                    if (isDestination) {
                        // Direct encounter with destination
                        outgoing.add(
                            OutgoingPacketSelection(
                                packetDto = toDto(packet, 1),
                                newLocalCopiesRemaining = packet.sprayCopiesRemaining
                            )
                        )
                    } else if (packet.sprayCopiesRemaining > 1) {
                        // Spray phase: give half (floor) and keep half (ceil)
                        val giveCopies = packet.sprayCopiesRemaining / 2
                        val keepCopies = packet.sprayCopiesRemaining - giveCopies
                        outgoing.add(
                            OutgoingPacketSelection(
                                packetDto = toDto(packet, giveCopies),
                                newLocalCopiesRemaining = keepCopies
                            )
                        )
                    }
                    // Else: packet.sprayCopiesRemaining == 1 -> Wait Phase (no intermediate relay)
                }
            }
        }

        return outgoing
    }
}
