package com.damarquez.putz.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.LanConnectionEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanConnectionsScreen(
    onNavigateUp: () -> Unit,
    viewModel: LanConnectionsViewModel,
) {
    val connections by viewModel.connections.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val testState by viewModel.testState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<LanConnectionEntity?>(null) }
    var deleteConfirmId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }

    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Delete connection?") },
            text = { Text("The connection will be removed. Your NAS files are not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConnection(id)
                        deleteConfirmId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("Cancel") }
            },
        )
    }

    if (showAddDialog) {
        LanConnectionDialog(
            connection = null,
            initialPassword = "",
            testState = testState,
            onTestConnection = { host, user, pass, share ->
                viewModel.testConnection(host, user, pass, share)
            },
            onResetTest = { viewModel.resetTestState() },
            onDismiss = {
                showAddDialog = false
                viewModel.resetTestState()
            },
            onConfirm = { label, host, user, pass, share ->
                viewModel.addConnection(label, host, user, pass, share)
                showAddDialog = false
                viewModel.resetTestState()
            },
        )
    }

    editingConnection?.let { conn ->
        LanConnectionDialog(
            connection = conn,
            initialPassword = viewModel.getPassword(conn.id),
            testState = testState,
            onTestConnection = { host, user, pass, share ->
                viewModel.testConnection(host, user, pass, share)
            },
            onResetTest = { viewModel.resetTestState() },
            onDismiss = {
                editingConnection = null
                viewModel.resetTestState()
            },
            onConfirm = { label, host, user, pass, share ->
                viewModel.updateConnection(conn.id, label, host, user, pass, share)
                editingConnection = null
                viewModel.resetTestState()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LAN Connections") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add connection")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        if (connections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = "No LAN connections yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = "Tap + to add a NAS or server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                items(connections, key = { it.id }) { conn ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingConnection = conn }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conn.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${conn.host}  ·  \\\\${conn.host}\\${conn.shareName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editingConnection = conn }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleteConfirmId = conn.id }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LanConnectionDialog(
    connection: LanConnectionEntity?,
    initialPassword: String,
    testState: TestConnectionState,
    onTestConnection: (host: String, username: String, password: String, shareName: String) -> Unit,
    onResetTest: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (label: String, host: String, username: String, password: String, shareName: String) -> Unit,
) {
    var label by remember { mutableStateOf(connection?.label ?: "") }
    var host by remember { mutableStateOf(connection?.host ?: "") }
    var username by remember { mutableStateOf(connection?.username ?: "") }
    var password by remember { mutableStateOf(initialPassword) }
    var shareName by remember { mutableStateOf(connection?.shareName ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connection == null) "Add LAN Connection" else "Edit Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it; onResetTest() },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. Home NAS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; onResetTest() },
                    label = { Text("Host / IP Address") },
                    placeholder = { Text("e.g. 192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; onResetTest() },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onResetTest() },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shareName,
                    onValueChange = { shareName = it; onResetTest() },
                    label = { Text("Share Name") },
                    placeholder = { Text("e.g. Books") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onTestConnection(host.trim(), username.trim(), password, shareName.trim()) },
                        enabled = testState !is TestConnectionState.Testing && host.isNotBlank() && shareName.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (testState is TestConnectionState.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Test Connection")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    when (testState) {
                        is TestConnectionState.Success -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        is TestConnectionState.Failed -> Icon(
                            Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        else -> Spacer(modifier = Modifier.size(24.dp))
                    }
                }

                if (testState is TestConnectionState.Failed) {
                    Text(
                        text = testState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(label.trim(), host.trim(), username.trim(), password, shareName.trim())
                },
                enabled = label.isNotBlank() && host.isNotBlank() && shareName.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
