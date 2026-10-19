package com.oppmesh.dtn.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppmesh.dtn.data.nearby.RadioState
import com.oppmesh.dtn.domain.model.RoutingStrategy
import com.oppmesh.dtn.util.FileUtils
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: ExperimentViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val nodeId by viewModel.nodeId.collectAsState()
    val radioState by viewModel.radioState.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val targetNodeId by viewModel.targetNodeId.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val strategy by viewModel.strategy.collectAsState()
    val sprayL by viewModel.sprayL.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val packets by viewModel.packets.collectAsState()
    val contactLogs by viewModel.contactLogs.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactsMap by viewModel.contactsMap.collectAsState()

    var showRegenConfirmDialog by remember { mutableStateOf(false) }
    var showCustomNodeDialog by remember { mutableStateOf(false) }
    var customNodeInput by remember { mutableStateOf("") }

    var showSaveContactDialog by remember { mutableStateOf(false) }
    var newContactAliasInput by remember { mutableStateOf("") }

    val recentPeers = remember(packets, contactLogs, nodeId) {
        val fromPackets = packets.flatMap { listOf(it.sourceNodeId, it.destinationNodeId) }
        val fromLogs = contactLogs.mapNotNull { it.peerNodeId }
        (fromPackets + fromLogs)
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != nodeId && it != "NONE" && it != "LOCAL" }
            .distinct()
            .take(8)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (targetNodeId.isBlank()) {
                Toast.makeText(context, "Please specify a Recipient ID first", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val (name, size) = FileUtils.getFileInfo(context, uri)
            if (size > FileUtils.MAX_FILE_SIZE_BYTES) {
                Toast.makeText(
                    context,
                    "File exceeds 20MB limit (${FileUtils.formatFileSize(size)}). Choose a file ≤ 20MB.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val savedFile = FileUtils.copyUriToAppStorage(context, uri, UUID.randomUUID().toString(), name)
                viewModel.sendFilePacket(name, size, savedFile.absolutePath)
                val destName = contactsMap[targetNodeId]?.let { "$it ($targetNodeId)" } ?: targetNodeId
                Toast.makeText(
                    context,
                    "File queued in DTN buffer for $destName: $name (${FileUtils.formatFileSize(size)})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Node Identity Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "MY MESH ADDRESS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = nodeId,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            letterSpacing = 2.sp
                        )
                    }

                    Row {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(nodeId))
                            Toast.makeText(context, "Copied address: $nodeId", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Address",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = { showRegenConfirmDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Regenerate Address",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Decentralized 8-character ID. Persisted across app restarts and reboots.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // Radio Control & Strategy Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Radio Control & Routing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Radio Status Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusColor = when (radioState) {
                        RadioState.IDLE -> Color.Gray
                        RadioState.SCANNING -> Color(0xFF2196F3) // Blue
                        RadioState.CONNECTING -> Color(0xFFFF9800) // Orange
                        RadioState.SYNCING -> Color(0xFF4CAF50) // Green
                    }
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = "Radio: ${radioState.name} (Active Peers: $peerCount)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Master Toggle Button
                val isRunning = radioState != RadioState.IDLE
                Button(
                    onClick = {
                        if (isRunning) viewModel.stopMeshRadio() else viewModel.startMeshRadio()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isRunning) "Stop Mesh Radio" else "Start Mesh Radio")
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Strategy Picker
                Text(text = "Routing Strategy:", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RoutingStrategy.entries.forEach { strat ->
                        FilterChip(
                            selected = (strategy == strat),
                            onClick = { viewModel.setStrategy(strat) },
                            label = {
                                Text(
                                    when (strat) {
                                        RoutingStrategy.EPIDEMIC -> "Epidemic (Spanning)"
                                        RoutingStrategy.SPRAY_AND_WAIT -> "Spray($sprayL)"
                                        RoutingStrategy.DIRECT_DELIVERY -> "Direct"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }

                if (strategy == RoutingStrategy.SPRAY_AND_WAIT) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "Copies (L):", style = MaterialTheme.typography.bodySmall)
                        listOf(2, 4, 6, 8).forEach { copies ->
                            FilterChip(
                                selected = (sprayL == copies),
                                onClick = { viewModel.setSprayL(copies) },
                                label = { Text("L=$copies") }
                            )
                        }
                    }
                }
            }
        }

        // Quick Compose Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Compose & Buffer Packet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Sender -> Destination Status Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "FROM (YOU)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(text = nodeId, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                    }
                    Text(text = "➔", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "TO (DESTINATION)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        val targetDisplayName = if (targetNodeId.isNotBlank()) {
                            contactsMap[targetNodeId]?.let { "$it ($targetNodeId)" } ?: targetNodeId
                        } else {
                            "Not Set"
                        }
                        Text(
                            text = targetDisplayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Destination Input
                OutlinedTextField(
                    value = targetNodeId,
                    onValueChange = { input ->
                        val cleaned = input.filter { it.isLetterOrDigit() }.take(8).uppercase()
                        viewModel.setTargetNodeId(cleaned)
                    },
                    label = { Text("Recipient Mesh Address (8-char ID)") },
                    placeholder = { Text("e.g. 7X9K2B1P") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (targetNodeId.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setTargetNodeId("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text?.trim()?.filter { it.isLetterOrDigit() }?.take(8)?.uppercase()
                                if (!clip.isNullOrEmpty()) {
                                    viewModel.setTargetNodeId(clip)
                                    Toast.makeText(context, "Pasted: $clip", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste Address")
                            }
                        }
                    }
                )

                // Quick Save Contact option if node is not yet in contacts
                if (targetNodeId.length == 8 && !contactsMap.containsKey(targetNodeId)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            newContactAliasInput = ""
                            showSaveContactDialog = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save '$targetNodeId' to Contacts", fontSize = 11.sp)
                    }
                }

                // Saved Contacts Chips
                if (contacts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Saved Contacts:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        contacts.forEach { contact ->
                            FilterChip(
                                selected = (targetNodeId == contact.nodeId),
                                onClick = { viewModel.setTargetNodeId(contact.nodeId) },
                                label = { Text("👤 ${contact.alias}", fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                            )
                        }
                    }
                }

                // Recent Peers Chips
                if (recentPeers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Recent / Discovered Peers:", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        recentPeers.forEach { peer ->
                            val labelText = contactsMap[peer]?.let { "👤 $it" } ?: peer
                            FilterChip(
                                selected = (targetNodeId == peer),
                                onClick = { viewModel.setTargetNodeId(peer) },
                                label = { Text(labelText, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { viewModel.setMessageText(it) },
                    label = { Text("Message Payload") },
                    placeholder = { Text("e.g. Hello via Store-Carry-Forward!") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (targetNodeId.isBlank()) {
                                Toast.makeText(context, "Please enter a Recipient Mesh Address", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.storeMessageInBuffer()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = messageText.isNotBlank() && targetNodeId.isNotBlank()
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send Text")
                    }

                    OutlinedButton(
                        onClick = {
                            if (targetNodeId.isBlank()) {
                                Toast.makeText(context, "Please enter a Recipient Mesh Address", Toast.LENGTH_SHORT).show()
                            } else {
                                filePickerLauncher.launch("*/*")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = targetNodeId.isNotBlank()
                    ) {
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send File (≤20MB)")
                    }
                }
            }
        }

        // Live Metrics Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Experiment Live Metrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(label = "Stored", value = metrics.totalStored.toString())
                    MetricItem(label = "Carried", value = metrics.totalCarried.toString())
                    MetricItem(label = "Delivered", value = metrics.totalDelivered.toString())
                    MetricItem(label = "Failures", value = metrics.failureCount.toString(), isError = metrics.failureCount > 0)
                }

                Spacer(modifier = Modifier.height(8.dp))
                val avgDelayText = if (metrics.totalDelivered > 0) {
                    "${String.format("%.1f", metrics.avgDelayMs / 1000.0)} s"
                } else {
                    "N/A"
                }
                Text(
                    text = "Average End-to-End Delivery Delay: $avgDelayText",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showRegenConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRegenConfirmDialog = false },
            title = { Text("Regenerate Mesh Address?") },
            text = {
                Text(
                    "This will generate a brand new 8-character ID for this device. Other nodes will need your new ID to send you packets. Are you sure?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.regenerateNodeId()
                        showRegenConfirmDialog = false
                        Toast.makeText(context, "New Mesh Address generated", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Regenerate", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCustomNodeDialog) {
        AlertDialog(
            onDismissRequest = { showCustomNodeDialog = false },
            title = { Text("Set Custom Mesh Address") },
            text = {
                Column {
                    Text("Enter an 8-character alphanumeric identity:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customNodeInput,
                        onValueChange = { customNodeInput = it.take(8).uppercase() },
                        singleLine = true,
                        placeholder = { Text("e.g. 7X9K2B1P") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = customNodeInput.trim().uppercase()
                        if (trimmed.isNotEmpty()) {
                            viewModel.setNodeId(trimmed)
                        }
                        showCustomNodeDialog = false
                    }
                ) {
                    Text("Set ID")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomNodeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSaveContactDialog) {
        AlertDialog(
            onDismissRequest = { showSaveContactDialog = false },
            title = { Text("Save Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Save node '$targetNodeId' with a friendly name:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = newContactAliasInput,
                        onValueChange = { newContactAliasInput = it },
                        label = { Text("Contact Name / Alias") },
                        placeholder = { Text("e.g. Alice, Headquarters, Node B") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newContactAliasInput.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.saveContact(targetNodeId, trimmed)
                            showSaveContactDialog = false
                            Toast.makeText(context, "Saved $trimmed ($targetNodeId)", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveContactDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MetricItem(label: String, value: String, isError: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
