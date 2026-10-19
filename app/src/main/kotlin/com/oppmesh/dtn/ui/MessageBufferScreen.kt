package com.oppmesh.dtn.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.oppmesh.dtn.data.local.PacketEntity
import com.oppmesh.dtn.data.nearby.RadioState
import com.oppmesh.dtn.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBufferScreen(viewModel: ExperimentViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val packets by viewModel.packets.collectAsState()
    val contactLogs by viewModel.contactLogs.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactsMap by viewModel.contactsMap.collectAsState()

    val localNodeId by viewModel.nodeId.collectAsState()
    val targetNodeId by viewModel.targetNodeId.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val radioState by viewModel.radioState.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()

    var filterDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var newChatInput by remember { mutableStateOf("") }
    var showSaveContactDialog by remember { mutableStateOf(false) }
    var saveContactAliasInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val destination = filterDestination ?: targetNodeId
            if (destination.isBlank()) {
                Toast.makeText(context, "Please select or enter a recipient ID first", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            if (targetNodeId != destination) {
                viewModel.setTargetNodeId(destination)
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
                val destDisplayName = contactsMap[destination]?.let { "$it ($destination)" } ?: destination
                Toast.makeText(
                    context,
                    "File queued in DTN buffer for $destDisplayName: $name (${FileUtils.formatFileSize(size)})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Filter packets based on selected peer or show all
    val displayedPackets = remember(packets, filterDestination, localNodeId) {
        val filtered = if (filterDestination == null) {
            packets
        } else {
            packets.filter {
                (it.sourceNodeId == localNodeId && it.destinationNodeId == filterDestination) ||
                        (it.sourceNodeId == filterDestination && it.destinationNodeId == localNodeId) ||
                        (it.sourceNodeId == filterDestination || it.destinationNodeId == filterDestination)
            }
        }
        filtered.sortedBy { it.createdAtEpochMs }
    }

    // Auto-scroll to latest message when new packets arrive
    LaunchedEffect(displayedPackets.size) {
        if (displayedPackets.isNotEmpty()) {
            listState.animateScrollToItem(displayedPackets.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Chat Header with Destination Filter & Radio State
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val headerTitle = if (filterDestination != null) {
                            contactsMap[filterDestination]?.let { "Chat with $it ($filterDestination)" } ?: "Chat with $filterDestination"
                        } else {
                            "DTN Mesh Stream"
                        }
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "My Address: $localNodeId",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(localNodeId))
                                    Toast.makeText(context, "Copied address: $localNodeId", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy My Address",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    // Radio State Pill
                    val (statusColor, statusText) = when (radioState) {
                        RadioState.IDLE -> Pair(Color.Gray, "Radio Off")
                        RadioState.SCANNING -> Pair(Color(0xFF2196F3), "Searching ($peerCount)")
                        RadioState.CONNECTING -> Pair(Color(0xFFFF9800), "Connecting")
                        RadioState.SYNCING -> Pair(Color(0xFF4CAF50), "Connected ($peerCount)")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Peer Selector Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Chat:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(
                        selected = (filterDestination == null),
                        onClick = { filterDestination = null },
                        label = { Text("All", fontSize = 12.sp) }
                    )

                    // Saved Contacts first
                    contacts.forEach { contact ->
                        FilterChip(
                            selected = (filterDestination == contact.nodeId),
                            onClick = {
                                filterDestination = if (filterDestination == contact.nodeId) null else contact.nodeId
                                viewModel.setTargetNodeId(contact.nodeId)
                            },
                            label = { Text("👤 ${contact.alias}", fontSize = 11.sp) }
                        )
                    }

                    // Other active peers not in saved contacts
                    val otherPeers = remember(packets, contactLogs, contacts, localNodeId, targetNodeId) {
                        val savedIds = contacts.map { it.nodeId }.toSet()
                        val fromPackets = packets.flatMap { listOf(it.sourceNodeId, it.destinationNodeId) }
                        val fromLogs = contactLogs.mapNotNull { it.peerNodeId }
                        (listOf(targetNodeId) + fromPackets + fromLogs)
                            .map { it.trim().uppercase() }
                            .filter { it.isNotBlank() && it != localNodeId && it != "NONE" && it != "LOCAL" && !savedIds.contains(it) }
                            .distinct()
                    }

                    otherPeers.forEach { peer ->
                        FilterChip(
                            selected = (filterDestination == peer),
                            onClick = {
                                filterDestination = if (filterDestination == peer) null else peer
                                viewModel.setTargetNodeId(peer)
                            },
                            label = { Text(peer, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        )
                    }

                    FilterChip(
                        selected = false,
                        onClick = {
                            newChatInput = ""
                            showNewChatDialog = true
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("New Chat", fontSize = 11.sp)
                            }
                        }
                    )
                }
            }
        }

        // Chat Messages Stream
        if (displayedPackets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val emptyNotice = if (filterDestination != null) {
                        val name = contactsMap[filterDestination]?.let { "$it ($filterDestination)" } ?: filterDestination
                        "No messages with $name yet."
                    } else {
                        "No messages buffered yet."
                    }
                    Text(
                        text = emptyNotice,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Packets addressed to this peer will store-carry-forward opportunistically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(displayedPackets, key = { it.packetId }) { packet ->
                    val isOutgoing = packet.sourceNodeId == localNodeId
                    val isFinalDestination = packet.destinationNodeId == localNodeId

                    ChatBubbleItem(
                        packet = packet,
                        isOutgoing = isOutgoing,
                        isFinalDestination = isFinalDestination,
                        localNodeId = localNodeId,
                        contactsMap = contactsMap
                    )
                }
            }
        }

        // Bottom Chat Input Bar (Direct In-Chat Sending)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val effectiveRecipient = filterDestination ?: targetNodeId
                if (effectiveRecipient.isNotBlank()) {
                    val recipientDisplay = contactsMap[effectiveRecipient]?.let { "$it ($effectiveRecipient)" } ?: effectiveRecipient
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Recipient: $recipientDisplay",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (!contactsMap.containsKey(effectiveRecipient)) {
                            Text(
                                text = "+ Save Contact",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.clickable {
                                    saveContactAliasInput = ""
                                    showSaveContactDialog = true
                                }
                            )
                        } else {
                            Text(
                                text = "Tap '+ New Chat' to change",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val dest = filterDestination ?: targetNodeId
                            if (dest.isBlank()) {
                                Toast.makeText(context, "Please select or enter a recipient ID first", Toast.LENGTH_SHORT).show()
                                showNewChatDialog = true
                            } else {
                                if (targetNodeId != dest) {
                                    viewModel.setTargetNodeId(dest)
                                }
                                filePickerLauncher.launch("*/*")
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach File (Max 20MB)",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { viewModel.setMessageText(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            val dest = filterDestination ?: targetNodeId
                            val destDisplay = contactsMap[dest] ?: dest
                            Text(
                                text = if (dest.isNotBlank()) "Message to $destDisplay..." else "Enter recipient (+ New Chat)...",
                                fontSize = 14.sp
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val dest = filterDestination ?: targetNodeId
                            if (dest.isBlank()) {
                                Toast.makeText(context, "Please select or enter a recipient ID first", Toast.LENGTH_SHORT).show()
                                showNewChatDialog = true
                            } else {
                                if (targetNodeId != dest) {
                                    viewModel.setTargetNodeId(dest)
                                }
                                viewModel.storeMessageInBuffer()
                            }
                        },
                        enabled = messageText.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (messageText.isNotBlank()) Color.White else Color.Gray
                        )
                    }
                }
            }
        }
    }

    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("Start Chat with Node") },
            text = {
                Column {
                    Text("Enter recipient's 8-character Mesh Address:", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newChatInput,
                        onValueChange = { newChatInput = it.filter { c -> c.isLetterOrDigit() }.take(8).uppercase() },
                        singleLine = true,
                        placeholder = { Text("e.g. 7X9K2B1P") },
                        trailingIcon = {
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text?.trim()?.filter { it.isLetterOrDigit() }?.take(8)?.uppercase()
                                if (!clip.isNullOrEmpty()) {
                                    newChatInput = clip
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste Address")
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newChatInput.trim().uppercase()
                        if (trimmed.isNotEmpty()) {
                            filterDestination = trimmed
                            viewModel.setTargetNodeId(trimmed)
                        }
                        showNewChatDialog = false
                    }
                ) {
                    Text("Open Chat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSaveContactDialog) {
        val targetToSave = filterDestination ?: targetNodeId
        AlertDialog(
            onDismissRequest = { showSaveContactDialog = false },
            title = { Text("Save Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Save node '$targetToSave' to your address book:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = saveContactAliasInput,
                        onValueChange = { saveContactAliasInput = it },
                        label = { Text("Contact Name / Alias") },
                        placeholder = { Text("e.g. Alice, HQ, Medical Tent") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = saveContactAliasInput.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.saveContact(targetToSave, trimmed)
                            showSaveContactDialog = false
                            Toast.makeText(context, "Saved contact: $trimmed", Toast.LENGTH_SHORT).show()
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
fun ChatBubbleItem(
    packet: PacketEntity,
    isOutgoing: Boolean,
    isFinalDestination: Boolean,
    localNodeId: String,
    contactsMap: Map<String, String> = emptyMap()
) {
    var expandedDetails by remember { mutableStateOf(false) }
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Alignment: Outgoing on Right, Incoming on Left
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start

    // Modern Bubble Shapes
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 3.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 3.dp, bottomEnd = 16.dp)
    }

    // Colors
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (isFinalDestination) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        // Relayed packet being carried
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else if (isFinalDestination) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .widthIn(min = 140.dp, max = 320.dp)
                .clickable { expandedDetails = !expandedDetails }
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Header (Sender -> Destination) with Alias Support
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val senderDisplay = contactsMap[packet.sourceNodeId]?.let { "$it (${packet.sourceNodeId})" } ?: packet.sourceNodeId
                    val destDisplay = contactsMap[packet.destinationNodeId]?.let { "$it (${packet.destinationNodeId})" } ?: packet.destinationNodeId

                    Text(
                        text = if (isOutgoing) "To: $destDisplay" else "From: $senderDisplay",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )

                    if (!isOutgoing && packet.destinationNodeId != localNodeId) {
                        Text(
                            text = "RELAYING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFF9800)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // File Attachment Card or Text Payload
                if (packet.fileName != null) {
                    val context = LocalContext.current
                    val isAvailableLocally = packet.filePath != null && File(packet.filePath).exists()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isOutgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = "File",
                                tint = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = packet.fileName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = textColor,
                                    maxLines = 1
                                )
                                Text(
                                    text = FileUtils.formatFileSize(packet.fileSizeBytes ?: 0L),
                                    fontSize = 10.sp,
                                    color = textColor.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            if (isAvailableLocally) {
                                Button(
                                    onClick = {
                                        FileUtils.openFileWithViewer(context, packet.filePath!!)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Open", fontSize = 10.sp)
                                }
                            } else {
                                Text(
                                    text = "Receiving...",
                                    fontSize = 10.sp,
                                    color = textColor.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                } else {
                    // Regular Text Payload
                    Text(
                        text = packet.payloadText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        fontWeight = FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Footer Row: Time + Status Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeFormat.format(Date(packet.createdAtEpochMs)),
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.7f)
                    )

                    // Delivery status badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (packet.status) {
                            "DELIVERED" -> {
                                val delaySec = if (packet.deliveredAtEpochMs != null) {
                                    (packet.deliveredAtEpochMs - packet.createdAtEpochMs) / 1000.0
                                } else 0.0
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Delivered",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${String.format("%.1f", delaySec)}s",
                                    fontSize = 10.sp,
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            "CARRIED" -> {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Carried",
                                    tint = Color(0xFF1976D2),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isOutgoing) "Relayed" else "Carried",
                                    fontSize = 10.sp,
                                    color = Color(0xFF1976D2),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            "STORED" -> {
                                Icon(
                                    imageVector = Icons.Default.HourglassTop,
                                    contentDescription = "Stored",
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Buffered",
                                    fontSize = 10.sp,
                                    color = Color(0xFFE65100),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Expandable Technical DTN Details
                AnimatedVisibility(visible = expandedDetails) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(textColor.copy(alpha = 0.15f))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Strategy: ${packet.strategy.name} (Copies: ${packet.sprayCopiesRemaining})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                        Text(
                            text = "Path: ${packet.hopPathCsv.replace(",", " ➔ ")} (Hops: ${packet.hopCount})",
                            fontSize = 10.sp,
                            color = textColor.copy(alpha = 0.8f)
                        )
                        Text(
                            text = "ID: ${packet.packetId.take(12)}... | Tx: ${packet.transmissionCount}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = textColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
