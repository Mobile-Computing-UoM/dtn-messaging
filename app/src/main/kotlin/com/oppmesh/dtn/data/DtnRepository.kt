package com.oppmesh.dtn.data

import android.content.Context
import com.oppmesh.dtn.data.local.ContactDao
import com.oppmesh.dtn.data.local.ContactEntity
import com.oppmesh.dtn.data.local.ContactLogDao
import com.oppmesh.dtn.data.local.ContactLogEntity
import com.oppmesh.dtn.data.local.OppMeshDatabase
import com.oppmesh.dtn.data.local.PacketDao
import com.oppmesh.dtn.data.local.PacketEntity
import com.oppmesh.dtn.domain.model.NetworkPacketDto
import com.oppmesh.dtn.domain.model.RoutingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DtnRepository(private val database: OppMeshDatabase) {

    private val packetDao: PacketDao = database.packetDao()
    private val contactLogDao: ContactLogDao = database.contactLogDao()
    private val contactDao: ContactDao = database.contactDao()

    val packetsFlow: Flow<List<PacketEntity>> = packetDao.getAllPacketsFlow()
    val logsFlow: Flow<List<ContactLogEntity>> = contactLogDao.getAllLogsFlow()
    val contactsFlow: Flow<List<ContactEntity>> = contactDao.getAllContactsFlow()

    suspend fun getActivePackets(): List<PacketEntity> = withContext(Dispatchers.IO) {
        packetDao.getActivePackets()
    }

    suspend fun getAllPacketsSync(): List<PacketEntity> = withContext(Dispatchers.IO) {
        packetDao.getAllPacketsSync()
    }

    suspend fun getAllLogsSync(): List<ContactLogEntity> = withContext(Dispatchers.IO) {
        contactLogDao.getAllLogsSync()
    }

    suspend fun storeOriginatedMessage(
        sourceNodeId: String,
        destinationNodeId: String,
        payloadText: String,
        strategy: RoutingStrategy,
        initialCopies: Int = 4
    ): PacketEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val copies = if (strategy == RoutingStrategy.SPRAY_AND_WAIT) initialCopies else 1
        val entity = PacketEntity(
            packetId = UUID.randomUUID().toString(),
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            payloadText = payloadText,
            strategy = strategy,
            sprayCopiesRemaining = copies,
            hopCount = 0,
            hopPathCsv = sourceNodeId,
            createdAtEpochMs = now,
            storedAtLocalEpochMs = now,
            deliveredAtEpochMs = null,
            ttlMillis = 3_600_000L,
            transmissionCount = 0,
            status = "STORED"
        )
        packetDao.insertPacket(entity)
        entity
    }

    suspend fun getPacketById(packetId: String): PacketEntity? = withContext(Dispatchers.IO) {
        packetDao.getPacketById(packetId)
    }

    suspend fun updatePacketFilePath(packetId: String, filePath: String) = withContext(Dispatchers.IO) {
        packetDao.updateFilePath(packetId, filePath)
    }

    suspend fun storeOriginatedFile(
        sourceNodeId: String,
        destinationNodeId: String,
        fileName: String,
        fileSizeBytes: Long,
        filePath: String,
        strategy: RoutingStrategy,
        initialCopies: Int = 4
    ): PacketEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val copies = if (strategy == RoutingStrategy.SPRAY_AND_WAIT) initialCopies else 1
        val formattedSize = com.oppmesh.dtn.util.FileUtils.formatFileSize(fileSizeBytes)
        val entity = PacketEntity(
            packetId = UUID.randomUUID().toString(),
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            payloadText = "[File] $fileName ($formattedSize)",
            strategy = strategy,
            sprayCopiesRemaining = copies,
            hopCount = 0,
            hopPathCsv = sourceNodeId,
            createdAtEpochMs = now,
            storedAtLocalEpochMs = now,
            deliveredAtEpochMs = null,
            ttlMillis = 3_600_000L,
            transmissionCount = 0,
            status = "STORED",
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            filePath = filePath
        )
        packetDao.insertPacket(entity)
        entity
    }

    suspend fun receiveInboundPacket(
        dto: NetworkPacketDto,
        localNodeId: String,
        clockOffsetMs: Long
    ): PacketEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = packetDao.getPacketById(dto.packetId)
        val isFinalDest = dto.destinationNodeId == localNodeId

        val newHopPath = if (dto.hopPathCsv.isEmpty()) {
            localNodeId
        } else if (!dto.hopPathCsv.endsWith(localNodeId)) {
            "${dto.hopPathCsv},$localNodeId"
        } else {
            dto.hopPathCsv
        }

        val updatedEntity = if (existing != null) {
            val updatedCopies = if (dto.strategy == RoutingStrategy.SPRAY_AND_WAIT) {
                existing.sprayCopiesRemaining + dto.sprayCopiesToTransfer
            } else {
                existing.sprayCopiesRemaining
            }
            existing.copy(
                sprayCopiesRemaining = updatedCopies,
                status = if (isFinalDest) "DELIVERED" else existing.status,
                deliveredAtEpochMs = if (isFinalDest) (existing.deliveredAtEpochMs ?: now) else existing.deliveredAtEpochMs,
                fileName = dto.fileName ?: existing.fileName,
                fileSizeBytes = dto.fileSizeBytes ?: existing.fileSizeBytes
            )
        } else {
            PacketEntity(
                packetId = dto.packetId,
                sourceNodeId = dto.sourceNodeId,
                destinationNodeId = dto.destinationNodeId,
                payloadText = dto.payloadText,
                strategy = dto.strategy,
                sprayCopiesRemaining = dto.sprayCopiesToTransfer,
                hopCount = dto.hopCount + 1,
                hopPathCsv = newHopPath,
                createdAtEpochMs = dto.createdAtEpochMs,
                storedAtLocalEpochMs = now,
                deliveredAtEpochMs = if (isFinalDest) now else null,
                ttlMillis = dto.ttlMillis,
                transmissionCount = 0,
                status = if (isFinalDest) "DELIVERED" else "CARRIED",
                fileName = dto.fileName,
                fileSizeBytes = dto.fileSizeBytes,
                filePath = null
            )
        }

        packetDao.insertPacket(updatedEntity)
        updatedEntity
    }

    suspend fun markDeliveredBatch(packetIds: List<String>, deliveredAtEpochMs: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            if (packetIds.isNotEmpty()) {
                packetDao.markDeliveredBatch(packetIds, deliveredAtEpochMs)
            }
        }

    suspend fun updateCopiesRemaining(packetId: String, remainingCopies: Int) = withContext(Dispatchers.IO) {
        packetDao.updateCopiesRemaining(packetId, remainingCopies)
    }

    suspend fun incrementTransmission(packetId: String) = withContext(Dispatchers.IO) {
        packetDao.incrementTransmission(packetId)
    }

    suspend fun markCarried(packetId: String) = withContext(Dispatchers.IO) {
        packetDao.markCarried(packetId)
    }

    suspend fun logContactEvent(
        sessionId: String,
        localNodeId: String,
        peerEndpointId: String,
        peerNodeId: String?,
        eventType: String,
        statusCode: Int,
        statusName: String,
        handshakeDurationMs: Long? = null,
        contactDurationMs: Long? = null,
        packetsSent: Int = 0,
        packetsReceived: Int = 0,
        notes: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val entity = ContactLogEntity(
            sessionId = sessionId,
            localNodeId = localNodeId,
            peerEndpointId = peerEndpointId,
            peerNodeId = peerNodeId,
            eventType = eventType,
            statusCode = statusCode,
            statusName = statusName,
            timestampEpochMs = System.currentTimeMillis(),
            handshakeDurationMs = handshakeDurationMs,
            contactDurationMs = contactDurationMs,
            packetsSent = packetsSent,
            packetsReceived = packetsReceived,
            notes = notes
        )
        contactLogDao.insertLog(entity)
    }

    suspend fun exportPacketsCsv(context: Context): File = withContext(Dispatchers.IO) {
        val packets = packetDao.getAllPacketsSync()
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "packets_export.csv")
        val header = "packet_id,source_node,destination_node,payload,strategy,spray_copies,hop_count,hop_path,created_at_epoch,stored_at_epoch,delivered_at_epoch,ttl_ms,transmissions,status,e2e_delay_ms\n"
        val rows = packets.joinToString("\n") { p ->
            val delay = if (p.deliveredAtEpochMs != null) (p.deliveredAtEpochMs - p.createdAtEpochMs).toString() else ""
            "${p.packetId},${p.sourceNodeId},${p.destinationNodeId},\"${p.payloadText.replace("\"", "\"\"")}\",${p.strategy.name},${p.sprayCopiesRemaining},${p.hopCount},\"${p.hopPathCsv}\",${p.createdAtEpochMs},${p.storedAtLocalEpochMs},${p.deliveredAtEpochMs ?: ""},${p.ttlMillis},${p.transmissionCount},${p.status},$delay"
        }
        file.writeText(header + rows)
        file
    }

    suspend fun exportContactLogsCsv(context: Context): File = withContext(Dispatchers.IO) {
        val logs = contactLogDao.getAllLogsSync()
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "contact_logs_export.csv")
        val header = "id,session_id,local_node,peer_endpoint,peer_node,event_type,status_code,status_name,timestamp_epoch,handshake_duration_ms,contact_duration_ms,packets_sent,packets_received,notes\n"
        val rows = logs.joinToString("\n") { l ->
            "${l.id},${l.sessionId},${l.localNodeId},${l.peerEndpointId},${l.peerNodeId ?: ""},${l.eventType},${l.statusCode},\"${l.statusName}\",${l.timestampEpochMs},${l.handshakeDurationMs ?: ""},${l.contactDurationMs ?: ""},${l.packetsSent},${l.packetsReceived},\"${l.notes.replace("\"", "\"\"")}\""
        }
        file.writeText(header + rows)
        file
    }

    suspend fun saveContact(nodeId: String, alias: String, notes: String = "") = withContext(Dispatchers.IO) {
        val cleanedNodeId = nodeId.trim().uppercase()
        val cleanedAlias = alias.trim()
        if (cleanedNodeId.isNotBlank()) {
            val contact = ContactEntity(
                nodeId = cleanedNodeId,
                alias = if (cleanedAlias.isNotBlank()) cleanedAlias else cleanedNodeId,
                notes = notes.trim(),
                updatedAtEpochMs = System.currentTimeMillis()
            )
            contactDao.insertOrUpdate(contact)
        }
    }

    suspend fun deleteContact(nodeId: String) = withContext(Dispatchers.IO) {
        contactDao.deleteByNodeId(nodeId.trim().uppercase())
    }

    suspend fun getContact(nodeId: String): ContactEntity? = withContext(Dispatchers.IO) {
        contactDao.getContact(nodeId.trim().uppercase())
    }

    suspend fun getAllContactsSync(): List<ContactEntity> = withContext(Dispatchers.IO) {
        contactDao.getAllContactsSync()
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        packetDao.clearAll()
        contactLogDao.clearAll()
    }

    companion object {
        @Volatile
        private var INSTANCE: DtnRepository? = null

        fun getInstance(context: Context): DtnRepository {
            return INSTANCE ?: synchronized(this) {
                val db = OppMeshDatabase.getInstance(context)
                val repo = DtnRepository(db)
                INSTANCE = repo
                repo
            }
        }
    }
}
