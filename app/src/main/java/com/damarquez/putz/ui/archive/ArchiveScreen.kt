package com.damarquez.putz.ui.archive

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damarquez.putz.data.model.ArchiveDestination
import com.damarquez.putz.data.model.ArchiveEntry
import com.damarquez.putz.data.model.ExtractionProgress
import com.damarquez.putz.ui.components.ErrorView
import com.damarquez.putz.ui.components.FileIconProvider
import com.damarquez.putz.ui.theme.LocalAppStyling

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    onNavigateUp: () -> Unit,
    viewModel: ArchiveViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val lanPickerState by viewModel.lanPickerState.collectAsState()
    val putioPickerState by viewModel.putioPickerState.collectAsState()
    val context = LocalContext.current
    var showDestinationPicker by remember { mutableStateOf(false) }

    val pickLocalFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.extract(ArchiveDestination.Local(it.toString()))
        }
    }

    BackHandler(enabled = putioPickerState != null) {
        if (!viewModel.putioPickerNavigateUp()) viewModel.closePutioPicker()
    }
    BackHandler(enabled = lanPickerState != null) {
        if (!viewModel.lanPickerNavigateUp()) viewModel.closeLanPicker()
    }
    BackHandler(enabled = lanPickerState == null && putioPickerState == null) {
        val handledInternally = viewModel.navigateUp()
        if (!handledInternally) onNavigateUp()
    }

    val s = uiState as? ArchiveUiState.Success

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = viewModel.archiveName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (s != null && s.currentDir.isNotEmpty()) {
                            Text(
                                text = s.currentDir,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val handled = viewModel.navigateUp()
                        if (!handled) onNavigateUp()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (s != null && s.isSelectionMode) {
                        Text(
                            text = "${s.selectedEntries.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                        Button(
                            onClick = { showDestinationPicker = true },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("Extract")
                        }
                    } else if (s != null) {
                        Button(
                            onClick = { showDestinationPicker = true },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("Extract all")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                is ArchiveUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is ArchiveUiState.Error -> {
                    ErrorView(
                        message = state.message,
                        onRetry = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is ArchiveUiState.Success -> {
                    if (state.visibleEntries.isEmpty()) {
                        Text(
                            text = "Empty folder",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                        ) {
                            items(state.visibleEntries, key = { it.path }) { entry ->
                                ArchiveEntryItem(
                                    entry = entry,
                                    isSelected = entry in state.selectedEntries,
                                    isSelectionMode = state.isSelectionMode,
                                    onClick = {
                                        if (state.isSelectionMode) {
                                            viewModel.toggleSelection(entry)
                                        } else if (entry.isDirectory) {
                                            viewModel.enterDirectory(entry)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(entry) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    // Extraction overlay
                    when (val progress = state.extractionProgress) {
                        is ExtractionProgress.Working -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = "Extracting…",
                                        modifier = Modifier.padding(top = 12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        is ExtractionProgress.Done -> {
                            AlertDialog(
                                onDismissRequest = { viewModel.dismissExtractionResult() },
                                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                                title = { Text("Extraction complete") },
                                text = { Text("${progress.count} file(s) extracted successfully.") },
                                confirmButton = {
                                    TextButton(onClick = { viewModel.dismissExtractionResult() }) {
                                        Text("OK")
                                    }
                                },
                            )
                        }
                        is ExtractionProgress.Error -> {
                            AlertDialog(
                                onDismissRequest = { viewModel.dismissExtractionResult() },
                                title = { Text("Extraction failed") },
                                text = { Text(progress.message) },
                                confirmButton = {
                                    TextButton(onClick = { viewModel.dismissExtractionResult() }) {
                                        Text("OK")
                                    }
                                },
                            )
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    // Destination picker bottom sheet
    if (showDestinationPicker) {
        val sheetState = rememberModalBottomSheetState()
        val lanConnections by remember {
            viewModel.lanFilesRepository.getConnections()
        }.collectAsState(initial = emptyList())

        ModalBottomSheet(
            onDismissRequest = { showDestinationPicker = false },
            sheetState = sheetState,
        ) {
            Text(
                text = "Extract to…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            if (viewModel.isPutio) {
                ListItem(
                    headlineContent = { Text("put.io…") },
                    leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showDestinationPicker = false
                        viewModel.openPutioPicker()
                    },
                )
                HorizontalDivider()
            }
            ListItem(
                headlineContent = { Text("Local folder…") },
                leadingContent = { Icon(Icons.Default.Smartphone, contentDescription = null) },
                modifier = Modifier.clickable {
                    showDestinationPicker = false
                    pickLocalFolderLauncher.launch(null)
                },
            )
            if (lanConnections.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "LAN shares",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                lanConnections.forEach { conn ->
                    ListItem(
                        headlineContent = { Text(conn.label) },
                        supportingContent = { Text("\\\\${conn.host}\\${conn.shareName}") },
                        leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showDestinationPicker = false
                            viewModel.openLanPicker(
                                conn.id,
                                conn.label,
                                viewModel.defaultLanPath(conn.id),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }

    // put.io folder picker
    putioPickerState?.let { picker ->
        val pickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closePutioPicker() },
            sheetState = pickerSheetState,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (!viewModel.putioPickerNavigateUp()) viewModel.closePutioPicker()
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "put.io",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (picker.currentFolderName.isNotEmpty()) {
                        Text(
                            text = picker.currentFolderName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.confirmPutioExtraction() }) {
                    Text("Extract here")
                }
            }
            HorizontalDivider()
            if (picker.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (picker.dirs.isEmpty()) {
                Text(
                    text = "No subfolders",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                ) {
                    items(picker.dirs, key = { it.id }) { dir ->
                        ListItem(
                            headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.clickable { viewModel.putioPickerEnterDir(dir) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // LAN folder picker
    lanPickerState?.let { picker ->
        val pickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeLanPicker() },
            sheetState = pickerSheetState,
        ) {
            // Header row: [back] [label + path ···] [Extract here]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (!viewModel.lanPickerNavigateUp()) viewModel.closeLanPicker()
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = picker.connectionLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val displayPath = if (picker.currentPath.isEmpty()) "/" else "/${picker.currentPath}"
                    Text(
                        text = displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.confirmLanExtraction() }) {
                    Text("Extract here")
                }
            }
            HorizontalDivider()
            if (picker.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (picker.dirs.isEmpty()) {
                Text(
                    text = "No subfolders",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                ) {
                    items(picker.dirs, key = { it.lanPath ?: it.name }) { dir ->
                        ListItem(
                            headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.clickable { viewModel.lanPickerEnterDir(dir) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveEntryItem(
    entry: ArchiveEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val styling = LocalAppStyling.current
    val cornerRadius = styling.cornerRadiusDp.dp
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else Color.Transparent
    val extension = entry.name.substringAfterLast('.', "")
    val customRes: Int? = when {
        entry.isDirectory -> FileIconProvider.folder
        else -> FileIconProvider.forExtension(extension)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(44.dp),
        ) {
            if (customRes != null) {
                Icon(
                    painter = painterResource(id = customRes),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (!entry.isDirectory && extension.isNotEmpty() && extension.length <= 5 && customRes == null) {
                Text(
                    text = extension.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory && entry.size > 0L) {
                Text(
                    text = formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
