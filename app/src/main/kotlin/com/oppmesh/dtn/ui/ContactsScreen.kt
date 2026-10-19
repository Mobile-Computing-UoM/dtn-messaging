package com.oppmesh.dtn.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppmesh.dtn.data.local.ContactEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(viewModel: ExperimentViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val contacts by viewModel.contacts.collectAsState()
    val localNodeId by viewModel.nodeId.collectAsState()
    val packets by viewModel.packets.collectAsState()
    val contactLogs by viewModel.contactLogs.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactEntity?>(null) }
    var deleteConfirmContact by remember { mutableStateOf<ContactEntity?>(null) }

    var inputNodeId by remember { mutableStateOf("") }
    var inputAlias by remember { mutableStateOf("") }
    var inputNotes by remember { mutableStateOf("") }

    val savedNodeIds = remember(contacts) { contacts.map { it.nodeId }.toSet() }

    val unsavedDiscoveredPeers = remember(packets, contactLogs, savedNodeIds, localNodeId) {
        val fromPackets = packets.flatMap { listOf(it.sourceNodeId, it.destinationNodeId) }
        val fromLogs = contactLogs.mapNotNull { it.peerNodeId }
        (fromPackets + fromLogs)
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != localNodeId && it != "NONE" && it != "LOCAL" && !savedNodeIds.contains(it) }
            .distinct()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    inputNodeId = ""
                    inputAlias = ""
                    inputNotes = ""
                    editingContact = null
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Contact")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            // Header Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Mesh Address Book",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Save 8-character node identities with friendly names (e.g. 'Alice', 'HQ', 'Relay-1'). Names persist across launches and sync with Chat and Dashboard.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Saved Contacts Section
            item {
                Text(
                    text = "Saved Contacts (${contacts.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (contacts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No saved contacts yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                inputNodeId = ""
                                inputAlias = ""
                                inputNotes = ""
                                editingContact = null
                                showAddDialog = true
                            }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add First Contact")
                            }
                        }
                    }
                }
            } else {
                items(contacts, key = { it.nodeId }) { contact ->
                    ContactCardItem(
                        contact = contact,
                        onCopyId = {
                            clipboardManager.setText(AnnotatedString(contact.nodeId))
                            Toast.makeText(context, "Copied ${contact.nodeId}", Toast.LENGTH_SHORT).show()
                        },
                        onChat = {
                            viewModel.openChatWith(contact.nodeId)
                        },
                        onSetDestination = {
                            viewModel.setTargetNodeId(contact.nodeId)
                            Toast.makeText(context, "Target set to ${contact.alias} (${contact.nodeId})", Toast.LENGTH_SHORT).show()
                        },
                        onEdit = {
                            editingContact = contact
                            inputNodeId = contact.nodeId
                            inputAlias = contact.alias
                            inputNotes = contact.notes
                            showAddDialog = true
                        },
                        onDelete = {
                            deleteConfirmContact = contact
                        }
                    )
                }
            }

            // Unsaved Discovered Radio Peers Section
            if (unsavedDiscoveredPeers.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discovered Mesh Peers (${unsavedDiscoveredPeers.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Nodes heard over BLE/Wi-Fi or packets that are not yet saved to your address book:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(unsavedDiscoveredPeers, key = { it }) { peerId ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = peerId,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Nearby / Encountered Node",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.openChatWith(peerId)
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Chat", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = {
                                        inputNodeId = peerId
                                        inputAlias = ""
                                        inputNotes = ""
                                        editingContact = null
                                        showAddDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Save", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add / Edit Dialog
    if (showAddDialog) {
        val isEditing = editingContact != null
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(if (isEditing) "Edit Contact" else "Save New Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputNodeId,
                        onValueChange = { inputNodeId = it.filter { c -> c.isLetterOrDigit() }.take(8).uppercase() },
                        label = { Text("Mesh Address (8-char ID)") },
                        placeholder = { Text("e.g. HX3ASMBW") },
                        enabled = !isEditing,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (!isEditing) {
                                IconButton(onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()?.filter { it.isLetterOrDigit() }?.take(8)?.uppercase()
                                    if (!clip.isNullOrEmpty()) {
                                        inputNodeId = clip
                                    }
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste Address")
                                }
                            }
                        }
                    )

                    OutlinedTextField(
                        value = inputAlias,
                        onValueChange = { inputAlias = it },
                        label = { Text("Contact Name / Alias") },
                        placeholder = { Text("e.g. Alice, Headquarters, Node B") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = inputNotes,
                        onValueChange = { inputNotes = it },
                        label = { Text("Notes (Optional)") },
                        placeholder = { Text("e.g. Field hospital relay, Phone 2") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanId = inputNodeId.trim().uppercase()
                        val cleanAlias = inputAlias.trim()
                        if (cleanId.length < 8) {
                            Toast.makeText(context, "Node ID must be 8 alphanumeric characters", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.saveContact(cleanId, if (cleanAlias.isNotBlank()) cleanAlias else cleanId, inputNotes)
                            showAddDialog = false
                            Toast.makeText(context, "Contact saved: ${cleanAlias.ifBlank { cleanId }}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (deleteConfirmContact != null) {
        val contactToDelete = deleteConfirmContact!!
        AlertDialog(
            onDismissRequest = { deleteConfirmContact = null },
            title = { Text("Delete Contact?") },
            text = {
                Text("Remove '${contactToDelete.alias}' (${contactToDelete.nodeId}) from your address book?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteContact(contactToDelete.nodeId)
                        deleteConfirmContact = null
                        Toast.makeText(context, "Contact deleted", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmContact = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ContactCardItem(
    contact: ContactEntity,
    onCopyId: () -> Unit,
    onChat: () -> Unit,
    onSetDestination: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.alias.take(1).uppercase(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Column {
                        Text(
                            text = contact.alias,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contact.nodeId,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = onCopyId, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy ID",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Contact", tint = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Contact", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (contact.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = contact.notes,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onChat,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chat", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onSetDestination,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Target", fontSize = 12.sp)
                }
            }
        }
    }
}
