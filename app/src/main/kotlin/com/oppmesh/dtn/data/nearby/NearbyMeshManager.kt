package com.oppmesh.dtn.data.nearby

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.oppmesh.dtn.data.DtnRepository
import com.oppmesh.dtn.domain.model.FrameType
import com.oppmesh.dtn.domain.model.NetworkPacketDto
import com.oppmesh.dtn.domain.model.WireFrame
import com.oppmesh.dtn.domain.routing.RoutingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.oppmesh.dtn.domain.routing.OutgoingPacketSelection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RadioState {
    IDLE,
    SCANNING,
    CONNECTING,
    SYNCING
}

class NearbyMeshManager(
    private val context: Context,
    private val repository: DtnRepository,
    var localNodeId: String = "NODE-A"
) {
    companion object {
        private const val TAG = "NearbyMeshManager"
        const val SERVICE_ID = "com.oppmesh.dtn"
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val TIE_BREAK_FALLBACK_DELAY_MS = 3_000L
        private const val PERIODIC_SYNC_INTERVAL_MS = 4_000L
    }

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _radioState = MutableStateFlow(RadioState.IDLE)
    val radioState: StateFlow<RadioState> = _radioState.asStateFlow()

    private val _activePeersCount = MutableStateFlow(0)
    val activePeersCount: StateFlow<Int> = _activePeersCount.asStateFlow()

    private val connectedPeers = ConcurrentHashMap<String, PeerSession>()
    private val discoveredTimestamps = ConcurrentHashMap<String, Long>()
    private val discoveredEndpointNames = ConcurrentHashMap<String, String>()
    private val fallbackRunnables = ConcurrentHashMap<String, Runnable>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()

    private val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()
    private val pendingFilePackets = ConcurrentHashMap<Long, Pair<String, String>>()
    private val completedTransferPayloadIds = ConcurrentHashMap.newKeySet<Long>()

    private var isMeshRunning = false

    private data class PeerSession(
        val sessionId: String,
        val endpointId: String,
        var peerNodeId: String?,
        val connectedAtMs: Long,
        var handshakeDurationMs: Long? = null,
        var clockOffsetMs: Long = 0L,
        var packetsSent: Int = 0,
        var packetsReceived: Int = 0,
        val peerKnownPacketIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val peerDeliveredPacketIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    )

    private fun isInitiator(local: String, remote: String): Boolean {
        val cleanLocal = local.trim()
        val cleanRemote = remote.trim()
        if (cleanRemote.isEmpty()) return true
        return cleanLocal < cleanRemote
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isMeshRunning) return
            if (connectedPeers.isEmpty() && pendingConnections.isEmpty()) {
                Log.d(TAG, "Watchdog: 15s idle cycle restart")
                scope.launch {
                    repository.logContactEvent(
                        sessionId = UUID.randomUUID().toString(),
                        localNodeId = localNodeId,
                        peerEndpointId = "NONE",
                        peerNodeId = null,
                        eventType = "WATCHDOG_RESET",
                        statusCode = 0,
                        statusName = "DUTY_CYCLE_RESTART",
                        notes = "Cycling radio after 15s idle"
                    )
                    restartAdvertisingAndDiscovery()
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (!isMeshRunning) return
            if (connectedPeers.isNotEmpty()) {
                scope.launch {
                    pushPendingPacketsToConnectedPeers()
                }
            }
            mainHandler.postDelayed(this, PERIODIC_SYNC_INTERVAL_MS)
        }
    }

    fun startMesh(nodeId: String = localNodeId) {
        if (isMeshRunning) return
        localNodeId = nodeId
        isMeshRunning = true
        _radioState.value = RadioState.SCANNING

        startAdvertising()
        startDiscovery()

        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        mainHandler.postDelayed(periodicSyncRunnable, PERIODIC_SYNC_INTERVAL_MS)
        Log.i(TAG, "Mesh radio started for node: $localNodeId")
    }

    fun stopMesh() {
        if (!isMeshRunning) return
        isMeshRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(periodicSyncRunnable)
        fallbackRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        fallbackRunnables.clear()
        pendingConnections.clear()

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        connectedPeers.clear()
        discoveredTimestamps.clear()
        _activePeersCount.value = 0
        _radioState.value = RadioState.IDLE
        Log.i(TAG, "Mesh radio stopped")
    }

    private fun restartAdvertisingAndDiscovery() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        mainHandler.postDelayed({
            if (isMeshRunning) {
                startAdvertising()
                startDiscovery()
            }
        }, 500)
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            localNodeId,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising successfully started as $localNodeId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed: ${e.message}", e)
            scope.launch {
                repository.logContactEvent(
                    sessionId = UUID.randomUUID().toString(),
                    localNodeId = localNodeId,
                    peerEndpointId = "LOCAL",
                    peerNodeId = null,
                    eventType = "FAILURE",
                    statusCode = -1,
                    statusName = "ADVERTISING_FAILED",
                    notes = e.message ?: "Advertising failure"
                )
            }
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery successfully started")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed: ${e.message}", e)
            scope.launch {
                repository.logContactEvent(
                    sessionId = UUID.randomUUID().toString(),
                    localNodeId = localNodeId,
                    peerEndpointId = "LOCAL",
                    peerNodeId = null,
                    eventType = "FAILURE",
                    statusCode = -1,
                    statusName = "DISCOVERY_FAILED",
                    notes = e.message ?: "Discovery failure"
                )
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName.trim()
            val discoveryTime = System.currentTimeMillis()
            discoveredTimestamps[endpointId] = discoveryTime
            discoveredEndpointNames[endpointId] = peerName
            Log.i(TAG, "Discovered endpoint: $endpointId ($peerName)")

            scope.launch {
                repository.logContactEvent(
                    sessionId = UUID.randomUUID().toString(),
                    localNodeId = localNodeId,
                    peerEndpointId = endpointId,
                    peerNodeId = peerName,
                    eventType = "DISCOVERED",
                    statusCode = 0,
                    statusName = "ENDPOINT_FOUND",
                    notes = "Discovered peer $peerName"
                )
            }

            if (connectedPeers.containsKey(endpointId) || pendingConnections.contains(endpointId)) {
                Log.d(TAG, "Endpoint $endpointId already connected or connecting, skipping duplicate request")
                return
            }

            // Lexicographical tie-breaking:
            // smaller node requests connection; larger waits passively
            if (isInitiator(localNodeId, peerName)) {
                Log.d(TAG, "Tie-break WON: $localNodeId vs $peerName -> Requesting connection")
                requestConnectionTo(endpointId, peerName)
            } else {
                Log.d(TAG, "Tie-break LOST: $localNodeId vs $peerName -> Waiting passively with fallback timer")
                val fallbackRunnable = Runnable {
                    if (!connectedPeers.containsKey(endpointId) && !pendingConnections.contains(endpointId)) {
                        Log.w(TAG, "Fallback timer expired for $peerName -> Requesting connection")
                        requestConnectionTo(endpointId, peerName)
                    }
                }
                fallbackRunnables[endpointId] = fallbackRunnable
                mainHandler.postDelayed(fallbackRunnable, TIE_BREAK_FALLBACK_DELAY_MS)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            discoveredTimestamps.remove(endpointId)
            fallbackRunnables.remove(endpointId)?.let { mainHandler.removeCallbacks(it) }
            pendingConnections.remove(endpointId)
        }
    }

    private fun requestConnectionTo(endpointId: String, peerName: String) {
        if (!pendingConnections.add(endpointId)) {
            Log.d(TAG, "Already connecting to $endpointId, skipping duplicate request")
            return
        }

        _radioState.value = RadioState.CONNECTING
        scope.launch {
            repository.logContactEvent(
                sessionId = UUID.randomUUID().toString(),
                localNodeId = localNodeId,
                peerEndpointId = endpointId,
                peerNodeId = peerName,
                eventType = "CONN_REQUESTED",
                statusCode = 0,
                statusName = "REQUEST_CONNECTION",
                notes = "Initiating connection to $peerName"
            )
        }

        connectionsClient.requestConnection(localNodeId, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Connection request sent to $endpointId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Connection request failed to $endpointId: ${e.message}", e)
                pendingConnections.remove(endpointId)
                if (connectedPeers.isEmpty() && pendingConnections.isEmpty()) {
                    _radioState.value = RadioState.SCANNING
                }
                scope.launch {
                    repository.logContactEvent(
                        sessionId = UUID.randomUUID().toString(),
                        localNodeId = localNodeId,
                        peerEndpointId = endpointId,
                        peerNodeId = peerName,
                        eventType = "FAILURE",
                        statusCode = -1,
                        statusName = "REQUEST_CONNECTION_FAILED",
                        notes = e.message ?: "Connection request failure"
                    )
                }
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated with $endpointId (${info.endpointName})")
            val remoteName = info.endpointName.trim()
            if (remoteName.isNotBlank()) {
                discoveredEndpointNames[endpointId] = remoteName
            }
            pendingConnections.add(endpointId)
            fallbackRunnables.remove(endpointId)?.let { mainHandler.removeCallbacks(it) }
            _radioState.value = RadioState.CONNECTING

            // Automatically accept incoming connections
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "Accepted connection from $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Accept connection failed: ${e.message}", e)
                    pendingConnections.remove(endpointId)
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            fallbackRunnables.remove(endpointId)?.let { mainHandler.removeCallbacks(it) }
            val statusCode = resolution.status.statusCode
            val statusName = resolution.status.statusMessage ?: resolution.status.toString()
            val now = System.currentTimeMillis()
            val discoveredTime = discoveredTimestamps[endpointId]
            val handshakeDurationMs = if (discoveredTime != null) now - discoveredTime else null

            if (statusCode == 0) { // STATUS_OK
                val sessionId = UUID.randomUUID().toString()
                val peerName = discoveredEndpointNames[endpointId]
                val session = PeerSession(
                    sessionId = sessionId,
                    endpointId = endpointId,
                    peerNodeId = peerName,
                    connectedAtMs = now,
                    handshakeDurationMs = handshakeDurationMs
                )
                connectedPeers[endpointId] = session
                _activePeersCount.value = connectedPeers.size
                _radioState.value = RadioState.SYNCING
                Log.i(TAG, "Connected to $endpointId ($peerName, Handshake: ${handshakeDurationMs}ms)")

                scope.launch {
                    repository.logContactEvent(
                        sessionId = sessionId,
                        localNodeId = localNodeId,
                        peerEndpointId = endpointId,
                        peerNodeId = peerName,
                        eventType = "CONNECTED",
                        statusCode = statusCode,
                        statusName = statusName,
                        handshakeDurationMs = handshakeDurationMs,
                        notes = "Connected successfully"
                    )
                    // Step 1: Send HELLO_SUMMARY
                    sendHelloSummary(endpointId)
                }
            } else {
                Log.w(TAG, "Connection failed to $endpointId: code=$statusCode ($statusName)")
                scope.launch {
                    repository.logContactEvent(
                        sessionId = UUID.randomUUID().toString(),
                        localNodeId = localNodeId,
                        peerEndpointId = endpointId,
                        peerNodeId = discoveredEndpointNames[endpointId],
                        eventType = "FAILURE",
                        statusCode = statusCode,
                        statusName = statusName,
                        handshakeDurationMs = handshakeDurationMs,
                        notes = "Connection resolution failure"
                    )
                }
                if (connectedPeers.isEmpty() && pendingConnections.isEmpty()) {
                    _radioState.value = RadioState.SCANNING
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingConnections.remove(endpointId)
            discoveredEndpointNames.remove(endpointId)
            fallbackRunnables.remove(endpointId)?.let { mainHandler.removeCallbacks(it) }
            val session = connectedPeers.remove(endpointId)
            _activePeersCount.value = connectedPeers.size
            if (connectedPeers.isEmpty() && pendingConnections.isEmpty()) {
                _radioState.value = RadioState.SCANNING
            }

            val now = System.currentTimeMillis()
            val contactDurationMs = session?.let { now - it.connectedAtMs }
            Log.i(TAG, "Disconnected from $endpointId (Duration: ${contactDurationMs}ms)")

            scope.launch {
                repository.logContactEvent(
                    sessionId = session?.sessionId ?: UUID.randomUUID().toString(),
                    localNodeId = localNodeId,
                    peerEndpointId = endpointId,
                    peerNodeId = session?.peerNodeId,
                    eventType = "DISCONNECTED",
                    statusCode = 0,
                    statusName = "DISCONNECTED",
                    handshakeDurationMs = session?.handshakeDurationMs,
                    contactDurationMs = contactDurationMs,
                    packetsSent = session?.packetsSent ?: 0,
                    packetsReceived = session?.packetsReceived ?: 0,
                    notes = "Session terminated"
                )
            }

            // Refresh discovery cache after disconnect
            mainHandler.postDelayed({
                if (isMeshRunning && connectedPeers.isEmpty()) {
                    Log.d(TAG, "Restarting discovery after disconnect to refresh peer discovery cache")
                    restartAdvertisingAndDiscovery()
                }
            }, 2000L)
        }
    }

    private suspend fun sendHelloSummary(endpointId: String) {
        val activePackets = repository.getActivePackets()
        val allPackets = repository.getAllPacketsSync()
        val knownIds = activePackets.map { it.packetId }
        val deliveredIds = allPackets.filter { it.isDelivered }.map { it.packetId }

        val frame = WireFrame(
            frameType = FrameType.HELLO_SUMMARY,
            senderNodeId = localNodeId,
            senderWallClockMs = System.currentTimeMillis(),
            knownPacketIds = knownIds,
            deliveredPacketIds = deliveredIds
        )
        sendWireFrame(endpointId, frame)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val jsonStr = String(bytes, Charsets.UTF_8)
                    scope.launch {
                        handleIncomingWireFrame(endpointId, jsonStr)
                    }
                }
                Payload.Type.FILE -> {
                    Log.i(TAG, "Incoming FILE payload id=${payload.id} from $endpointId")
                    incomingFilePayloads[payload.id] = payload
                    // If transfer SUCCESS update already fired before onPayloadReceived
                    if (completedTransferPayloadIds.contains(payload.id)) {
                        checkAndProcessCompletedFile(payload.id)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.i(TAG, "File transfer SUCCESS for payloadId=${update.payloadId}")
                    completedTransferPayloadIds.add(update.payloadId)
                    checkAndProcessCompletedFile(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    Log.w(TAG, "File transfer FAILURE for payloadId=${update.payloadId}")
                    incomingFilePayloads.remove(update.payloadId)
                    pendingFilePackets.remove(update.payloadId)
                    completedTransferPayloadIds.remove(update.payloadId)
                }
            }
        }
    }

    private fun checkAndProcessCompletedFile(payloadId: Long) {
        if (!completedTransferPayloadIds.contains(payloadId)) {
            Log.d(TAG, "File payload $payloadId not completed transfer yet, waiting for SUCCESS update...")
            return
        }
        val pair = pendingFilePackets[payloadId] ?: return
        val payload = incomingFilePayloads[payloadId] ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val (packetId, fileName) = pair
                val oppDir = File(context.filesDir, "oppmesh_files").apply { mkdirs() }
                val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destFile = File(oppDir, "${packetId}_$safeName")

                val filePayload = payload.asFile()
                val uri = filePayload?.asUri()
                val pfd = filePayload?.asParcelFileDescriptor()

                var copied = false

                // 1. Primary mechanism for Android 10+ Scoped Storage: ContentResolver openInputStream(uri)
                if (uri != null) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        copied = destFile.exists() && destFile.length() > 0
                        if (copied) {
                            Log.i(TAG, "Read file payload via ContentResolver URI successfully (${destFile.length()} bytes)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed reading file payload via ContentResolver URI: ${e.message}")
                    }
                }

                // 2. Secondary fallback: ParcelFileDescriptor
                if (!copied && pfd != null) {
                    try {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        pfd.close()
                        copied = destFile.exists() && destFile.length() > 0
                        if (copied) {
                            Log.i(TAG, "Read file payload via ParcelFileDescriptor successfully (${destFile.length()} bytes)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed reading file payload via PFD: ${e.message}")
                    }
                }

                if (copied && destFile.exists() && destFile.length() > 0) {
                    incomingFilePayloads.remove(payloadId)
                    pendingFilePackets.remove(payloadId)
                    completedTransferPayloadIds.remove(payloadId)
                    repository.updatePacketFilePath(packetId, destFile.absolutePath)
                    Log.i(TAG, "Successfully received and stored file for packet $packetId at ${destFile.absolutePath} (${destFile.length()} bytes)")
                } else {
                    Log.e(TAG, "Failed to copy file payload $payloadId: copied=$copied, exists=${destFile.exists()}, len=${destFile.length()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling completed file payload $payloadId: ${e.message}", e)
            }
        }
    }

    private suspend fun transmitPacketSelections(endpointId: String, selections: List<OutgoingPacketSelection>) {
        if (selections.isEmpty()) return
        val session = connectedPeers[endpointId] ?: return
        val now = System.currentTimeMillis()

        val filePayloadsToSend = mutableListOf<Pair<Long, Payload>>()
        val dtos = selections.map { sel ->
            val p = sel.packetDto
            if (p.fileName != null) {
                val entity = repository.getPacketById(p.packetId)
                val filePath = entity?.filePath
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists() && file.canRead()) {
                        try {
                            val filePayload = Payload.fromFile(file)
                            filePayloadsToSend.add(Pair(filePayload.id, filePayload))
                            p.copy(filePayloadId = filePayload.id)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create Payload.fromFile for ${file.name}: ${e.message}", e)
                            p
                        }
                    } else {
                        p
                    }
                } else {
                    p
                }
            } else {
                p
            }
        }

        val bundleFrame = WireFrame(
            frameType = FrameType.PACKET_BUNDLE,
            senderNodeId = localNodeId,
            senderWallClockMs = now,
            packets = dtos
        )
        sendWireFrame(endpointId, bundleFrame)

        // Send corresponding file payloads immediately
        for ((payloadId, filePayload) in filePayloadsToSend) {
            try {
                connectionsClient.sendPayload(endpointId, filePayload)
                Log.i(TAG, "Dispatched file payload id=$payloadId to $endpointId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch file payload id=$payloadId to $endpointId: ${e.message}", e)
            }
        }

        // Update local copy state
        selections.forEach { sel ->
            repository.updateCopiesRemaining(sel.packetDto.packetId, sel.newLocalCopiesRemaining)
            repository.incrementTransmission(sel.packetDto.packetId)
            repository.markCarried(sel.packetDto.packetId)
            session.peerKnownPacketIds.add(sel.packetDto.packetId)
        }
        session.packetsSent += dtos.size
        Log.i(TAG, "Transmitted ${dtos.size} packets (${filePayloadsToSend.size} files) to peer ${session.peerNodeId}")
    }

    private suspend fun handleIncomingWireFrame(endpointId: String, jsonStr: String) {
        val frame = try {
            Json.decodeFromString<WireFrame>(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode WireFrame: ${e.message}")
            return
        }

        val session = connectedPeers[endpointId] ?: return
        session.peerNodeId = frame.senderNodeId

        val now = System.currentTimeMillis()
        val clockSkewMs = now - frame.senderWallClockMs
        session.clockOffsetMs = clockSkewMs

        when (frame.frameType) {
            FrameType.HELLO_SUMMARY -> {
                Log.d(TAG, "Received HELLO_SUMMARY from ${frame.senderNodeId} (Clock skew: ${clockSkewMs}ms)")

                session.peerKnownPacketIds.addAll(frame.knownPacketIds)
                session.peerDeliveredPacketIds.addAll(frame.deliveredPacketIds)

                // Anti-entropy ACK healing: Mark any local packets known delivered by peer
                if (frame.deliveredPacketIds.isNotEmpty()) {
                    repository.markDeliveredBatch(frame.deliveredPacketIds)
                }

                // Routing decision: select packets to transmit to peer
                val localPackets = repository.getActivePackets()
                val selections = RoutingEngine.selectPacketsForPeer(
                    localPackets = localPackets,
                    peerNodeId = frame.senderNodeId,
                    peerKnownPacketIds = session.peerKnownPacketIds,
                    peerDeliveredPacketIds = session.peerDeliveredPacketIds,
                    currentEpochMs = now
                )

                if (selections.isNotEmpty()) {
                    transmitPacketSelections(endpointId, selections)
                }
            }

            FrameType.PACKET_BUNDLE -> {
                Log.d(TAG, "Received PACKET_BUNDLE with ${frame.packets.size} packets from ${frame.senderNodeId}")
                val newlyDeliveredIds = mutableListOf<String>()
                var hasRelayedPackets = false

                frame.packets.forEach { dto ->
                    session.peerKnownPacketIds.add(dto.packetId)
                    val storedEntity = repository.receiveInboundPacket(dto, localNodeId, clockSkewMs)
                    session.packetsReceived++
                    if (storedEntity.isDelivered && storedEntity.destinationNodeId.equals(localNodeId, ignoreCase = true)) {
                        newlyDeliveredIds.add(storedEntity.packetId)
                    } else {
                        hasRelayedPackets = true
                    }

                    val payloadId = dto.filePayloadId
                    val fileName = dto.fileName
                    if (payloadId != null && fileName != null) {
                        pendingFilePackets[payloadId] = Pair(dto.packetId, fileName)
                        checkAndProcessCompletedFile(payloadId)
                    }
                }

                // If any delivered packets, reply with DELIVERY_ACK
                if (newlyDeliveredIds.isNotEmpty()) {
                    val ackFrame = WireFrame(
                        frameType = FrameType.DELIVERY_ACK,
                        senderNodeId = localNodeId,
                        senderWallClockMs = now,
                        deliveredPacketIds = newlyDeliveredIds
                    )
                    sendWireFrame(endpointId, ackFrame)
                    Log.i(TAG, "Sent DELIVERY_ACK for ${newlyDeliveredIds.size} packets to ${frame.senderNodeId}")
                }

                // Spanning Forward: If this node is carrying packets for other destinations,
                // forward them immediately to other connected peers!
                if (hasRelayedPackets) {
                    pushPendingPacketsToConnectedPeers(excludeEndpointId = endpointId)
                }

                repository.logContactEvent(
                    sessionId = session.sessionId,
                    localNodeId = localNodeId,
                    peerEndpointId = endpointId,
                    peerNodeId = frame.senderNodeId,
                    eventType = "SYNC_COMPLETE",
                    statusCode = 0,
                    statusName = "SUCCESS",
                    handshakeDurationMs = session.handshakeDurationMs,
                    packetsSent = session.packetsSent,
                    packetsReceived = session.packetsReceived,
                    notes = "Exchanged packets with ${frame.senderNodeId}"
                )
            }

            FrameType.DELIVERY_ACK -> {
                Log.d(TAG, "Received DELIVERY_ACK for ${frame.deliveredPacketIds.size} packets from ${frame.senderNodeId}")
                session.peerDeliveredPacketIds.addAll(frame.deliveredPacketIds)
                repository.markDeliveredBatch(frame.deliveredPacketIds)

                repository.logContactEvent(
                    sessionId = session.sessionId,
                    localNodeId = localNodeId,
                    peerEndpointId = endpointId,
                    peerNodeId = frame.senderNodeId,
                    eventType = "SYNC_COMPLETE",
                    statusCode = 0,
                    statusName = "DELIVERY_ACK_RECEIVED",
                    handshakeDurationMs = session.handshakeDurationMs,
                    packetsSent = session.packetsSent,
                    packetsReceived = session.packetsReceived,
                    notes = "Confirmed delivery of ${frame.deliveredPacketIds.size} packets"
                )
            }
        }
    }

    fun notifyBufferUpdated() {
        scope.launch {
            if (connectedPeers.isEmpty()) {
                Log.d(TAG, "Buffer updated with 0 connected peers. Cycling discovery to find nearby nodes immediately.")
                restartAdvertisingAndDiscovery()
            } else {
                pushPendingPacketsToConnectedPeers()
            }
        }
    }

    private suspend fun pushPendingPacketsToConnectedPeers(excludeEndpointId: String? = null) {
        val now = System.currentTimeMillis()
        val localPackets = repository.getActivePackets()

        for ((endpointId, session) in connectedPeers) {
            if (excludeEndpointId != null && endpointId == excludeEndpointId) continue
            val peerNode = session.peerNodeId ?: continue
            val selections = RoutingEngine.selectPacketsForPeer(
                localPackets = localPackets,
                peerNodeId = peerNode,
                peerKnownPacketIds = session.peerKnownPacketIds,
                peerDeliveredPacketIds = session.peerDeliveredPacketIds,
                currentEpochMs = now
            )

            if (selections.isNotEmpty()) {
                transmitPacketSelections(endpointId, selections)
            }
        }
    }

    private fun sendWireFrame(endpointId: String, frame: WireFrame) {
        try {
            val jsonStr = Json.encodeToString(frame)
            val payload = Payload.fromBytes(jsonStr.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending wire frame: ${e.message}", e)
        }
    }
}
