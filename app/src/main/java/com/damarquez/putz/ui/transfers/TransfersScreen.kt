package com.damarquez.putz.ui.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.HistoryFileEntry
import com.damarquez.putz.data.model.MergedTransfer
import com.damarquez.putz.data.model.TransferGroup
import com.damarquez.putz.ui.components.ErrorView
import com.damarquez.putz.ui.navigation.Screen
import kotlinx.coroutines.launch
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    viewModel: TransfersViewModel,
    onNavigateToFiles: (Long, String, Long) -> Unit = { _, _, _ -> },
    onNavigateToHistory: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val showAddSheet by viewModel.showAddSheet.collectAsState()
    val prefillMagnet by viewModel.prefillMagnet.collectAsState()
    val addState by viewModel.addState.collectAsState()
    val navigationEvent by viewModel.navigationEvent.collectAsState()
    val resumeError by viewModel.resumeError.collectAsState()
    val queuedMessage by viewModel.queuedMessage.collectAsState()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val historySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var editNameTransferId by remember { mutableStateOf<Long?>(null) }
    var editNameValue by remember { mutableStateOf("") }
    var selectedHistoryEntry by remember { mutableStateOf<HistoryFileEntry?>(null) }

    LaunchedEffect(navigationEvent) {
        navigationEvent?.let { event ->
            if (event is TransfersNavigationEvent.NavigateToFiles) {
                onNavigateToFiles(event.parentId, event.folderName, event.highlightFileId)
                viewModel.onNavigationHandled()
            }
        }
    }

    LaunchedEffect(resumeError) {
        resumeError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onResumeErrorShown()
        }
    }

    LaunchedEffect(queuedMessage) {
        queuedMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onQueuedMessageShown()
        }
    }

    if (editNameTransferId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editNameTransferId = null },
            title = { Text("Edit display name") },
            text = {
                OutlinedTextField(
                    value = editNameValue,
                    onValueChange = { editNameValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateDisplayName(editNameTransferId!!, editNameValue)
                    editNameTransferId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editNameTransferId = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openAddSheet() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        when (val state = uiState) {
            is TransfersUiState.Loading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is TransfersUiState.Error -> {
                ErrorView(
                    message = state.message,
                    onRetry = { viewModel.refresh() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }

            is TransfersUiState.Success -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    if (state.grouped.isEmpty()) {
                        EmptyTransfersView(modifier = Modifier.fillMaxSize())
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            state.grouped.forEach { (group, transfers) ->
                                item(key = "header_${group.name}") {
                                    GroupHeader(group = group, count = transfers.size)
                                }
                                items(
                                    items = transfers,
                                    key = { it.transfer.id },
                                ) { merged ->
                                    TransferItem(
                                        merged = merged,
                                        onCopyMagnet = { link ->
                                            clipboardManager.setText(AnnotatedString(link))
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Magnet link copied")
                                            }
                                        },
                                        onStop = { viewModel.stopTransfer(it) },
                                        onRemove = {
                                            if (merged.isPendingLocal) {
                                                merged.historyEntry?.let(viewModel::cancelQueued)
                                            } else {
                                                viewModel.removeTransfer(it)
                                            }
                                        },
                                        onResume = { viewModel.resumeTransfer(it) },
                                        onActivate = { m -> m.historyEntry?.let(viewModel::activateQueued) },
                                        onEditName = { id, currentName ->
                                            editNameValue = currentName
                                            editNameTransferId = id
                                        },
                                        onGoToFiles = { fileId ->
                                            viewModel.goToFiles(fileId)
                                        },
                                        onTap = {
                                            selectedHistoryEntry = merged.historyEntry
                                                ?: synthesizeHistoryEntry(merged)
                                            viewModel.onTransferTapped(merged)
                                        },
                                    )
                                }
                                item(key = "divider_${group.name}") {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedHistoryEntry != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedHistoryEntry = null },
            sheetState = historySheetState,
        ) {
            HistoryDetailSheet(
                entry = selectedHistoryEntry!!,
                onEditLabel = { /* label editing not needed from transfers screen */ },
            )
        }
    }

    if (showAddSheet) {
        AddTransferSheet(
            sheetState = sheetState,
            prefill = prefillMagnet,
            addState = addState,
            onDismiss = { viewModel.dismissAddSheet() },
            onSubmit = { magnet, hide -> viewModel.submitTransfer(magnet, hide) },
            onSubmitAnyway = { magnet, hide -> viewModel.submitTransferAnyway(magnet, hide) },
        )
    }
}

@Composable
private fun GroupHeader(group: TransferGroup, count: Int) {
    ListItem(
        headlineContent = {
            Text(
                text = "${group.label}  ($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun synthesizeHistoryEntry(merged: MergedTransfer): HistoryFileEntry =
    HistoryFileEntry(
        infoHash = merged.transfer.hash ?: "",
        label = merged.appDisplayName,
        status = merged.transfer.status,
        addedAt = merged.transfer.createdAt
            ?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            ?: (System.currentTimeMillis() / 1000L),
        magnetUri = merged.magnetLink,
        putioId = merged.transfer.id,
        putioName = merged.transfer.name.takeIf { it != merged.appDisplayName },
        totalSizeBytes = merged.transfer.size.takeIf { it > 0 },
    )

@Composable
private fun EmptyTransfersView(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = "No transfers",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
