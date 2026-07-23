package com.damarquez.putz.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.ui.components.SyncProgressBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLibrariesScreen(
    onNavigateUp: () -> Unit,
    onNavigateToCalibreTransfers: () -> Unit,
    onNavigateToLanConnections: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val putioLocalLanConnectionId by viewModel.putioLocalLanConnectionId.collectAsState()
    val putioLocalLanPath by viewModel.putioLocalLanPath.collectAsState()
    val plexLibraryLanConnectionId by viewModel.plexLibraryLanConnectionId.collectAsState()
    val plexLibraryLanPath by viewModel.plexLibraryLanPath.collectAsState()
    val plexampLibraryLanConnectionId by viewModel.plexampLibraryLanConnectionId.collectAsState()
    val plexampLibraryLanPath by viewModel.plexampLibraryLanPath.collectAsState()
    val lanConnections by viewModel.lanConnections.collectAsState()
    val plexRootPickerState by viewModel.plexRootPickerState.collectAsState()
    val plexampRootPickerState by viewModel.plexampRootPickerState.collectAsState()
    val isSyncingLibrary by viewModel.isSyncingLibrary.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()

    var showLanConnectionPicker by remember { mutableStateOf(false) }
    var editingLanPath by remember { mutableStateOf<String?>(null) }
    var showPlexLanConnectionPicker by remember { mutableStateOf(false) }
    var showPlexampLanConnectionPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Libraries & Sync") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SettingsSectionHeader("Calibre Library Sync")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Button(
                    onClick = { viewModel.syncLibraryNow() },
                    enabled = !isSyncingLibrary,
                ) {
                    Text(if (isSyncingLibrary) "Syncing..." else "Sync Now")
                }
            }
            SyncProgressBanner(
                progress = syncProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ButtonRow(
                label = "Manage LAN Connections",
                onClick = onNavigateToLanConnections
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ButtonRow(
                label = "Manage Calibre Transfers",
                onClick = onNavigateToCalibreTransfers
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("put.io Local Sync")
            val selectedConn = lanConnections.find { it.id == putioLocalLanConnectionId }
            ButtonRow(
                label = if (selectedConn != null) "LAN connection: ${selectedConn.label}" else "Select LAN connection…",
                onClick = { showLanConnectionPicker = true }
            )
            if (selectedConn != null) {
                val lanPathState = editingLanPath ?: putioLocalLanPath
                OutlinedTextField(
                    value = lanPathState,
                    onValueChange = { editingLanPath = it },
                    label = { Text("Path within share") },
                    placeholder = { Text(".put.io") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    supportingText = { Text("Subfolder inside the share where files are stored") },
                )
                if (editingLanPath != null) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = {
                            viewModel.setPutioLocalLanPath(editingLanPath ?: "")
                            editingLanPath = null
                        }) { Text("Save") }
                        TextButton(onClick = { editingLanPath = null }) { Text("Cancel") }
                    }
                }
                ButtonRow(
                    label = "Remove put.io local sync",
                    onClick = {
                        viewModel.setPutioLocalLanConnection(null)
                        viewModel.setPutioLocalLanPath("")
                        editingLanPath = null
                    },
                    isError = true
                )
            }

            if (showLanConnectionPicker) {
                LanConnectionPickerDialog(
                    title = "Select LAN connection",
                    connections = lanConnections,
                    selectedId = putioLocalLanConnectionId,
                    onSelect = { id ->
                        viewModel.setPutioLocalLanConnection(id)
                        showLanConnectionPicker = false
                    },
                    onDismiss = { showLanConnectionPicker = false },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Plex Library")
            val selectedPlexConn = lanConnections.find { it.id == plexLibraryLanConnectionId }
            ButtonRow(
                label = if (selectedPlexConn != null) "LAN connection: ${selectedPlexConn.label}" else "Select LAN connection…",
                onClick = { showPlexLanConnectionPicker = true }
            )
            if (selectedPlexConn != null) {
                ButtonRow(
                    label = if (plexLibraryLanPath.isNotBlank()) "Library root: /${plexLibraryLanPath}" else "Browse for library root…",
                    onClick = { viewModel.openPlexRootPicker() }
                )
                ButtonRow(
                    label = "Remove Plex library",
                    onClick = {
                        viewModel.setPlexLibraryLanConnection(null)
                        viewModel.setPlexLibraryLanPath("")
                    },
                    isError = true
                )
            }

            if (showPlexLanConnectionPicker) {
                LanConnectionPickerDialog(
                    title = "Select LAN connection for Plex",
                    connections = lanConnections,
                    selectedId = plexLibraryLanConnectionId,
                    onSelect = { id ->
                        viewModel.setPlexLibraryLanConnection(id)
                        showPlexLanConnectionPicker = false
                    },
                    onDismiss = { showPlexLanConnectionPicker = false },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Plexamp Library")
            val selectedPlexampConn = lanConnections.find { it.id == plexampLibraryLanConnectionId }
            ButtonRow(
                label = if (selectedPlexampConn != null) "LAN connection: ${selectedPlexampConn.label}" else "Select LAN connection…",
                onClick = { showPlexampLanConnectionPicker = true }
            )
            if (selectedPlexampConn != null) {
                ButtonRow(
                    label = if (plexampLibraryLanPath.isNotBlank()) "Library root: /${plexampLibraryLanPath}" else "Browse for library root…",
                    onClick = { viewModel.openPlexampRootPicker() }
                )
                ButtonRow(
                    label = "Remove Plexamp library",
                    onClick = {
                        viewModel.setPlexampLibraryLanConnection(null)
                        viewModel.setPlexampLibraryLanPath("")
                    },
                    isError = true
                )
            }

            if (showPlexampLanConnectionPicker) {
                LanConnectionPickerDialog(
                    title = "Select LAN connection for Plexamp",
                    connections = lanConnections,
                    selectedId = plexampLibraryLanConnectionId,
                    onSelect = { id ->
                        viewModel.setPlexampLibraryLanConnection(id)
                        showPlexampLanConnectionPicker = false
                    },
                    onDismiss = { showPlexampLanConnectionPicker = false },
                )
            }
        }
    }

    plexRootPickerState?.let { pickerState ->
        com.damarquez.putz.ui.files.PlexFolderPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.dismissPlexRootPicker() },
            onNavigateUp = { viewModel.plexRootPickerNavigateUp() },
            onNavigateInto = { folder -> viewModel.browsePlexRootFolder(folder) },
            onSelect = { path -> viewModel.selectPlexRootPath(path) },
        )
    }

    plexampRootPickerState?.let { pickerState ->
        com.damarquez.putz.ui.files.PlexFolderPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.dismissPlexampRootPicker() },
            onNavigateUp = { viewModel.plexampRootPickerNavigateUp() },
            onNavigateInto = { folder -> viewModel.browsePlexampRootFolder(folder) },
            onSelect = { path -> viewModel.selectPlexampRootPath(path) },
        )
    }
}
