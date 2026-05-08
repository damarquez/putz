package com.damarquez.putz.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberModalBottomSheetState
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.ui.components.ErrorView
import com.damarquez.putz.ui.components.FileItem
import com.damarquez.putz.ui.navigation.Screen
import com.damarquez.putz.util.MetadataUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateToFolder: (Long, String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: FilesViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val accountInfo by viewModel.accountInfo.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var fileToDelete by remember { mutableStateOf<PutioFile?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) { selectedIds = emptySet() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }

    // Single-file Calibre send
    var selectedFileForCalibre by remember { mutableStateOf<PutioFile?>(null) }
    val calibreSheetState = rememberModalBottomSheetState()

    // Audiobook pack flow
    var audiobookPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedPackFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    val audiobookPackSheetState = rememberModalBottomSheetState()
    val audiobookConfirmSheetState = rememberModalBottomSheetState()

    val isRoot = viewModel.parentId == 0L
    val folderName = viewModel.folderName

    if (selectedFileForCalibre != null) {
        val singleFile = selectedFileForCalibre!!
        val (initialTitle, initialAuthor) = remember(singleFile) {
            MetadataUtils.extractMetadata(singleFile.name)
        }
        CalibreConfirmationSheet(
            displayName = singleFile.name,
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            sheetState = calibreSheetState,
            onDismiss = { selectedFileForCalibre = null },
            onConfirm = { title, author ->
                viewModel.sendToCalibre(singleFile, title, author)
                selectedFileForCalibre = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) }
        )
    }

    if (audiobookPackTriggerFile != null && selectedPackFiles == null) {
        val mp3Files = remember(uiState) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isMultiTrackAudio(it.name) }
                ?: emptyList()
        }
        AudiobookPackSheet(
            mp3Files = mp3Files,
            sheetState = audiobookPackSheetState,
            onDismiss = { audiobookPackTriggerFile = null },
            onConfirm = { files ->
                selectedPackFiles = files
                audiobookPackTriggerFile = null
            },
        )
    }

    if (selectedPackFiles != null) {
        val packFiles = selectedPackFiles!!
        val (initialTitle, initialAuthor) = remember(packFiles) {
            MetadataUtils.extractMetadata(packFiles.first().name)
        }
        CalibreConfirmationSheet(
            displayName = "${packFiles.size} MP3 files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            sheetState = audiobookConfirmSheetState,
            onDismiss = { selectedPackFiles = null },
            onConfirm = { title, author ->
                viewModel.sendAudiobookPack(packFiles, title, author)
                selectedPackFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) }
        )
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete \"${file.name}\"?") },
            text = {
                Text(
                    if (file.isFolder) "This folder and all its contents will be permanently deleted."
                    else "This file will be permanently deleted."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFiles(listOf(file.id))
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("Delete ${selectedIds.size} items?") },
            text = { Text("Selected files and folders will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFiles(selectedIds.toList())
                        selectedIds = emptySet()
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        Column {
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                            )
                            accountInfo?.let { info ->
                                if (isRoot) {
                                    Text(
                                        text = info.username,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        isSelectionMode -> IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                        !isRoot -> IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showBatchDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        IconButton(onClick = { viewModel.loadFiles(isRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                accountInfo?.let { info ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = info.username,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    text = info.mail ?: "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                StorageBar(
                                                    usedPercent = info.diskUsedPercent,
                                                    modifier = Modifier.padding(top = 6.dp),
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.AccountCircle,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {},
                                    )
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToSettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.signOut()
                                        onSignOut()
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        when (val state = uiState) {
            is FilesUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is FilesUiState.Error -> {
                ErrorView(
                    message = state.message,
                    onRetry = { viewModel.loadFiles() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }

            is FilesUiState.Success -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.loadFiles(isRefresh = true) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    if (state.files.isEmpty()) {
                        EmptyFolderView(
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = state.files,
                                key = { it.id },
                            ) { file ->
                                FileItem(
                                    file = file,
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedIds = if (file.id in selectedIds)
                                                selectedIds - file.id else selectedIds + file.id
                                        } else if (file.isFolder) {
                                            onNavigateToFolder(file.id, file.name)
                                        }
                                    },
                                    onLongClick = { selectedIds = selectedIds + file.id },
                                    onSendToCalibre = { selectedFileForCalibre = it },
                                    onSendAsAudiobookPack = { audiobookPackTriggerFile = it },
                                    onDelete = { fileToDelete = file },
                                    isSelected = file.id in selectedIds,
                                    isSelectionMode = isSelectionMode,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageBar(usedPercent: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(180.dp)) {
        LinearProgressIndicator(
            progress = { usedPercent.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "%.0f%% used".format(usedPercent * 100),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun EmptyFolderView(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = "This folder is empty",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
