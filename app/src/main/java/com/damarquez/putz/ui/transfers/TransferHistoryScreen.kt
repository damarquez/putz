package com.damarquez.putz.ui.transfers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHistoryScreen(
    viewModel: TransferHistoryViewModel,
    onNavigateUp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredEntries by viewModel.filteredEntries.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchInFiles by viewModel.searchInFiles.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val fileSearchInProgress by viewModel.fileSearchInProgress.collectAsState()
    val entryFiles by viewModel.entryFiles.collectAsState()
    val entryFilesLoading by viewModel.entryFilesLoading.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    var statusFilterMenuExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    var editingEntry by remember { mutableStateOf<HistoryFileEntry?>(null) }
    var editLabelValue by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<HistoryFileEntry?>(null) }
    var pendingRemoval by remember { mutableStateOf<HistoryFileEntry?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) runCatching { searchFocusRequester.requestFocus() }
        else {
            viewModel.setSearchQuery("")
            viewModel.setStatusFilter(HistoryStatusFilter.ALL)
        }
    }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.onActionMessageShown()
        }
    }

    if (editingEntry != null) {
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = { Text("Edit label") },
            text = {
                OutlinedTextField(
                    value = editLabelValue,
                    onValueChange = { editLabelValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = editingEntry ?: return@TextButton
                    if (editLabelValue.isNotBlank()) {
                        val newLabel = editLabelValue.trim()
                        viewModel.updateLabel(entry.infoHash, newLabel)
                        if (selectedEntry?.infoHash == entry.infoHash) {
                            selectedEntry = selectedEntry?.copy(label = newLabel)
                        }
                    }
                    editingEntry = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) { Text("Cancel") }
            },
        )
    }

    if (pendingRemoval != null) {
        val entry = pendingRemoval!!
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove from history?") },
            text = {
                Text(
                    "This permanently deletes \"${entry.resolvedName ?: entry.label}\" from history " +
                        "and cancels its put.io transfer job. You'll be able to re-add the same " +
                        "magnet/torrent afterward — this can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFromHistory(entry)
                    if (selectedEntry?.infoHash == entry.infoHash) selectedEntry = null
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }

    if (selectedEntry != null) {
        val entryHash = selectedEntry!!.infoHash.lowercase()
        LaunchedEffect(entryHash) { viewModel.loadFilesForEntry(entryHash) }

        Dialog(
            onDismissRequest = { selectedEntry = null },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                HistoryDetailSheet(
                    entry = selectedEntry!!,
                    searchQuery = searchQuery,
                    onEditLabel = {
                        editLabelValue = selectedEntry!!.label
                        editingEntry = selectedEntry
                    },
                    files = entryFiles[entryHash],
                    filesLoading = entryHash in entryFilesLoading,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search history…") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { isSearchActive = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    actions = {
                        // Per-file data is fetched server-side on demand (SEARCH_HISTORY_FILES)
                        // rather than filtered from an in-memory list — LAN is near-instant, but
                        // a Drive-only fallback can take up to ~30s, hence this indicator.
                        if (searchInFiles && fileSearchInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        val toggleBg = if (searchInFiles)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                .compositeOver(MaterialTheme.colorScheme.surface)
                        else Color.Transparent
                        IconButton(
                            onClick = { viewModel.setSearchInFiles(!searchInFiles) },
                            modifier = Modifier.background(toggleBg, shape = RoundedCornerShape(8.dp)),
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = if (searchInFiles) "Stop searching file names" else "Also search file names",
                                tint = if (searchInFiles) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { statusFilterMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter by status (${statusFilter.label})",
                                    tint = if (statusFilter != HistoryStatusFilter.ALL) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = statusFilterMenuExpanded,
                                onDismissRequest = { statusFilterMenuExpanded = false },
                            ) {
                                HistoryStatusFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = if (option == statusFilter) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            viewModel.setStatusFilter(option)
                                            statusFilterMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("Transfer History") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (uiState is HistoryUiState.Success) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        when (val state = uiState) {
            is HistoryUiState.Loading -> {
                Box(
                    Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            is HistoryUiState.Error -> {
                Box(
                    Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is HistoryUiState.Success -> {
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { viewModel.loadHistory() },
                    modifier = Modifier.padding(paddingValues),
                ) {
                    val displayEntries = filteredEntries
                    val isEmpty = displayEntries.isEmpty()
                    if (isEmpty) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when {
                                    searchQuery.isNotBlank() && statusFilter != HistoryStatusFilter.ALL ->
                                        "No ${statusFilter.label.lowercase()} results for \"$searchQuery\""
                                    searchQuery.isNotBlank() -> "No results for \"$searchQuery\""
                                    statusFilter != HistoryStatusFilter.ALL -> "No ${statusFilter.label.lowercase()} history"
                                    else -> "No history yet"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(displayEntries, key = { it.infoHash }) { entry ->
                                HistoryEntryRow(
                                    entry = entry,
                                    onClick = { selectedEntry = entry },
                                    onActivate = { viewModel.activateEntry(entry) },
                                    onMarkAsCompleted = { viewModel.markAsCompleted(entry) },
                                    onRemoveFromHistory = { pendingRemoval = entry },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryEntryRow(
    entry: HistoryFileEntry,
    onClick: () -> Unit,
    onActivate: () -> Unit,
    onMarkAsCompleted: () -> Unit,
    onRemoveFromHistory: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val isCompleted = entry.status == "COMPLETED"

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showMenu = true
            },
        ),
        headlineContent = {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                // Activate re-submits the magnet and would re-download everything — never offer
                // it for an entry that's already completed once.
                if (entry.magnetUri != null && !isCompleted) {
                    DropdownMenuItem(
                        text = { Text("Activate") },
                        onClick = {
                            showMenu = false
                            onActivate()
                        },
                    )
                }
                if (!isCompleted) {
                    DropdownMenuItem(
                        text = { Text("Mark as Completed") },
                        onClick = {
                            showMenu = false
                            onMarkAsCompleted()
                        },
                    )
                }
                if (isCompleted) {
                    DropdownMenuItem(
                        text = { Text("Remove from history") },
                        onClick = {
                            showMenu = false
                            onRemoveFromHistory()
                        },
                    )
                }
            }
            Text(
                text = entry.resolvedName ?: entry.label,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = buildStatusLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.resolvedName != null && entry.resolvedName != entry.label) {
                    Text(
                        text = "Label: ${entry.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.infoHash.take(8),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
        trailingContent = { MetadataSourceBadge(entry) },
    )
}

@Composable
private fun MetadataSourceBadge(entry: HistoryFileEntry) {
    val hasExternal = entry.resolvedName != null
    val hasPutio = !hasExternal && (entry.totalSizeBytes != null || !entry.files.isNullOrEmpty())
    if (!hasExternal && !hasPutio) return

    val text = if (hasExternal) "external" else "put.io"
    val bgColor = if (hasExternal) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (hasExternal) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer

    Box(
        modifier = Modifier
            .background(bgColor, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDetailSheet(
    entry: HistoryFileEntry,
    searchQuery: String = "",
    onEditLabel: () -> Unit,
    files: List<HistoryEntryFile>? = null,
    filesLoading: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()) }
    val highlightColor = Color(0xFF00C853).copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = highlightText(entry.resolvedName ?: entry.label, searchQuery, highlightColor),
                style = MaterialTheme.typography.titleLarge,
            )
            if (entry.resolvedName != null && entry.resolvedName != entry.label) {
                Text(
                    text = highlightText("Label: ${entry.label}", searchQuery, highlightColor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Status") },
            trailingContent = {
                val color = when (entry.status.uppercase()) {
                    "COMPLETED" -> MaterialTheme.colorScheme.primary
                    "ERROR", "FAILED", "ABANDONED" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = entry.status.lowercase().replaceFirstChar { it.uppercase() },
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
        ListItem(
            headlineContent = { Text("Added") },
            trailingContent = {
                Text(
                    text = dateFormat.format(Date(entry.addedAt * 1000L)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        entry.totalSizeBytes?.let { size ->
            ListItem(
                headlineContent = { Text("Size") },
                trailingContent = {
                    Text(
                        text = formatBytes(size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        entry.putioName
            ?.takeIf { it != entry.resolvedName && it != entry.label }
            ?.let { name ->
                ListItem(
                    headlineContent = { Text("put.io name") },
                    trailingContent = {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Info hash") },
            supportingContent = {
                Text(
                    text = entry.infoHash,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                IconButton(onClick = { clipboard.setText(AnnotatedString(entry.infoHash)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy info hash")
                }
            },
        )
        entry.magnetUri?.let { magnet ->
            ListItem(
                headlineContent = { Text("Magnet link") },
                supportingContent = {
                    Text(
                        text = magnet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(magnet)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy magnet link")
                    }
                },
            )
        }

        // Per-file listing is fetched on demand (FETCH_HISTORY_FILES) — never shipped in the
        // routine history sync, since some torrents are packs with thousands of files.
        if (filesLoading) {
            HorizontalDivider()
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
        } else if (!files.isNullOrEmpty()) {
            HorizontalDivider()
            ListItem(
                headlineContent = {
                    Text(
                        text = "Files (${files.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
            )
            files.forEach { file -> FileEntryRow(file, highlight = searchQuery) }
        }

        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            TextButton(
                onClick = onEditLabel,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("Edit label")
            }
        }
    }
}

@Composable
internal fun FileEntryRow(file: HistoryEntryFile, highlight: String = "") {
    val highlightColor = Color(0xFF00C853).copy(alpha = 0.45f)
    ListItem(
        headlineContent = {
            Text(
                text = highlightText(file.name, highlight, highlightColor),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = if (file.size > 0) {
            { Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
    )
}

private fun buildStatusLine(entry: HistoryFileEntry): String {
    val parts = mutableListOf<String>()
    parts.add(entry.status.lowercase().replaceFirstChar { it.uppercase() })
    entry.totalSizeBytes?.let { parts.add(formatBytes(it)) }
    entry.files?.size?.takeIf { it > 0 }?.let { parts.add("$it file${if (it == 1) "" else "s"}") }
    val date = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(entry.addedAt * 1000L))
    parts.add(date)
    return parts.joinToString(" · ")
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private fun highlightText(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val lower = text.lowercase()
        val q = query.trim().lowercase()
        var start = 0
        while (true) {
            val idx = lower.indexOf(q, start)
            if (idx == -1) break
            addStyle(SpanStyle(background = highlightColor), idx, idx + q.length)
            start = idx + q.length
        }
    }
}
