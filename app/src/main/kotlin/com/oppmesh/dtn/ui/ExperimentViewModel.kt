package com.oppmesh.dtn.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oppmesh.dtn.OppMeshApplication
import com.oppmesh.dtn.data.local.ContactEntity
import com.oppmesh.dtn.data.local.ContactLogEntity
import com.oppmesh.dtn.data.local.PacketEntity
import com.oppmesh.dtn.data.nearby.RadioState
import com.oppmesh.dtn.domain.model.RoutingStrategy
import com.oppmesh.dtn.service.MeshSyncForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class MeshMetrics(
    val totalStored: Int = 0,
    val totalCarried: Int = 0,
    val totalDelivered: Int = 0,
    val avgDelayMs: Double = 0.0,
    val failureCount: Int = 0
)

class ExperimentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OppMeshApplication
    private val repository = app.repository
    private val meshManager = app.meshManager

    val radioState: StateFlow<RadioState> = meshManager.radioState
    val peerCount: StateFlow<Int> = meshManager.activePeersCount

    val packets: StateFlow<List<PacketEntity>> = repository.packetsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactLogs: StateFlow<List<ContactLogEntity>> = repository.logsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contacts: StateFlow<List<ContactEntity>> = repository.contactsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactsMap: StateFlow<Map<String, String>> = repository.contactsFlow
        .map { list -> list.associate { it.nodeId to it.alias } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val identityManager = app.identityManager

    private val _nodeId = MutableStateFlow(identityManager.getOrCreateNodeId())
    val nodeId: StateFlow<String> = _nodeId.asStateFlow()

    private val _targetNodeId = MutableStateFlow(identityManager.getLastTargetNodeId())
    val targetNodeId: StateFlow<String> = _targetNodeId.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _strategy = MutableStateFlow(RoutingStrategy.EPIDEMIC)
    val strategy: StateFlow<RoutingStrategy> = _strategy.asStateFlow()

    private val _sprayL = MutableStateFlow(4)
    val sprayL: StateFlow<Int> = _sprayL.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    val metrics: StateFlow<MeshMetrics> = combine(packets, contactLogs) { packetList, logList ->
        val stored = packetList.count { it.status == "STORED" }
        val carried = packetList.count { it.status == "CARRIED" }
        val deliveredPackets = packetList.filter { it.deliveredAtEpochMs != null }
        val delivered = deliveredPackets.size
        val avgDelay = if (delivered > 0) {
            deliveredPackets.map { (it.deliveredAtEpochMs!! - it.createdAtEpochMs).toDouble() }.average()
        } else 0.0
        val failures = logList.count { it.eventType == "FAILURE" }

        MeshMetrics(
            totalStored = stored,
            totalCarried = carried,
            totalDelivered = delivered,
            avgDelayMs = avgDelay,
            failureCount = failures
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeshMetrics())

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun openChatWith(nodeId: String) {
        setTargetNodeId(nodeId)
        _selectedTab.value = 1
    }

    fun saveContact(nodeId: String, alias: String, notes: String = "") {
        viewModelScope.launch {
            repository.saveContact(nodeId, alias, notes)
        }
    }

    fun deleteContact(nodeId: String) {
        viewModelScope.launch {
            repository.deleteContact(nodeId)
        }
    }

    fun regenerateNodeId() {
        val newId = identityManager.regenerateNodeId()
        _nodeId.value = newId
        meshManager.localNodeId = newId
    }

    fun setNodeId(id: String) {
        val cleaned = id.trim().uppercase()
        if (cleaned.isNotEmpty()) {
            identityManager.setNodeId(cleaned)
            _nodeId.value = cleaned
            meshManager.localNodeId = cleaned
        }
    }

    fun setTargetNodeId(id: String) {
        val cleaned = id.trim().uppercase()
        _targetNodeId.value = cleaned
        identityManager.setLastTargetNodeId(cleaned)
    }

    fun setMessageText(text: String) {
        _messageText.value = text
    }

    fun setStrategy(strat: RoutingStrategy) {
        _strategy.value = strat
    }

    fun setSprayL(l: Int) {
        if (l >= 1) _sprayL.value = l
    }

    fun startMeshRadio() {
        MeshSyncForegroundService.startService(getApplication(), _nodeId.value)
    }

    fun stopMeshRadio() {
        MeshSyncForegroundService.stopService(getApplication())
    }

    fun storeMessageInBuffer() {
        val text = _messageText.value.trim()
        if (text.isEmpty()) return

        if (radioState.value == RadioState.IDLE) {
            startMeshRadio()
        }

        viewModelScope.launch {
            repository.storeOriginatedMessage(
                sourceNodeId = _nodeId.value,
                destinationNodeId = _targetNodeId.value,
                payloadText = text,
                strategy = _strategy.value,
                initialCopies = _sprayL.value
            )
            _messageText.value = ""
            meshManager.notifyBufferUpdated()
        }
    }

    fun sendFilePacket(fileName: String, fileSizeBytes: Long, filePath: String) {
        if (radioState.value == RadioState.IDLE) {
            startMeshRadio()
        }

        viewModelScope.launch {
            repository.storeOriginatedFile(
                sourceNodeId = _nodeId.value,
                destinationNodeId = _targetNodeId.value,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                filePath = filePath,
                strategy = _strategy.value,
                initialCopies = _sprayL.value
            )
            meshManager.notifyBufferUpdated()
        }
    }

    fun exportAndShareCsv(context: Context) {
        viewModelScope.launch {
            val packetsCsv = repository.exportPacketsCsv(context)
            val logsCsv = repository.exportContactLogsCsv(context)

            val packetsUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", packetsCsv)
            val logsUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logsCsv)

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(packetsUri, logsUri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share OppMesh Experiment Logs")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllData()
        }
    }
}
