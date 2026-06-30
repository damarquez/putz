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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.ui.files.CalibreConfirmationSheet
import com.damarquez.putz.ui.files.TransferRef
import com.damarquez.putz.util.MetadataUtils
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
import com.damarquez.putz.ui.files.MergeCandidateFile
import com.damarquez.putz.ui.files.MergeCandidateGroup
import com.damarquez.putz.ui.files.MergeContentType
import com.damarquez.putz.ui.files.MergeContentTypeChoiceDialog
import com.damarquez.putz.ui.files.MergePackSheet
import com.damarquez.putz.ui.files.MergeProcessChoiceDialog
import com.damarquez.putz.ui.files.AssemblyAppendSheet
import com.damarquez.putz.ui.files.assemblyIsProtected
import com.damarquez.putz.ui.files.matchesName
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
    val calibreSendStatus by viewModel.calibreSendStatus.collectAsState()
    val pendingAssemblies by viewModel.pendingAssemblies.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val context = LocalContext.current
    var showDestinationPicker by remember { mutableStateOf(false) }

    // Merge framework — see CONTRACTS.md "Merge framework" / archive-sourced files
    val archiveMergeChoice by viewModel.archiveMergeChoice.collectAsState()
    val archiveMergePickerState by viewModel.archiveMergePickerState.collectAsState()
    // Phase 1 result — held here until user picks a destination (new request or existing assembly)
    var pendingArchiveMergeFlat by remember { mutableStateOf<List<MergeCandidateFile>?>(null) }
    var pendingArchiveMergeGroups by remember { mutableStateOf<List<MergeCandidateGroup>?>(null) }
    var archiveMergeDestinationAssembly by remember { mutableStateOf<CalibreTransferEntity?>(null) }
    // Phase 2 — new-request path: forward to CalibreConfirmationSheet
    var selectedArchiveMergeFlatFiles by remember { mutableStateOf<List<MergeCandidateFile>?>(null) }
    var selectedArchiveMergeGroups by remember { mutableStateOf<List<MergeCandidateGroup>?>(null) }

    // Auto-forward when there are no assemblies to choose from
    LaunchedEffect(pendingArchiveMergeFlat) {
        val files = pendingArchiveMergeFlat ?: return@LaunchedEffect
        if (archiveMergeDestinationAssembly != null) return@LaunchedEffect
        if (pendingAssemblies.isEmpty()) {
            selectedArchiveMergeFlatFiles = files
            pendingArchiveMergeFlat = null
        }
    }
    LaunchedEffect(pendingArchiveMergeGroups) {
        val groups = pendingArchiveMergeGroups ?: return@LaunchedEffect
        if (archiveMergeDestinationAssembly != null) return@LaunchedEffect
        if (pendingAssemblies.isEmpty()) {
            selectedArchiveMergeGroups = groups
            pendingArchiveMergeGroups = null
        }
    }

    // Calibre send state
    var entryForCalibre by remember { mutableStateOf<ArchiveEntry?>(null) }
    var entryForAssembly by remember { mutableStateOf<ArchiveEntry?>(null) }
    var targetAssembly by remember { mutableStateOf<CalibreTransferEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage!!)
            viewModel.dismissSnackbar()
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                    onSendToCalibre = if (!entry.isDirectory) ({ entryForCalibre = entry }) else null,
                                    onAssembleBook = if (!entry.isDirectory) ({ entryForAssembly = entry }) else null,
                                    onMerge = when {
                                        entry.isDirectory -> ({ viewModel.openArchiveMergeChoice(entry) })
                                        MergeContentType.entries.any { it.matchesName(entry.name) } -> ({ viewModel.openArchiveFileMerge(entry) })
                                        else -> null
                                    },
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

                    // Calibre send overlay
                    when (val cs = calibreSendStatus) {
                        is CalibreSendStatus.Working -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = cs.text,
                                        modifier = Modifier.padding(top = 12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        is CalibreSendStatus.Error -> {
                            AlertDialog(
                                onDismissRequest = { viewModel.dismissCalibreSendStatus() },
                                title = { Text("Send to Calibre failed") },
                                text = { Text(cs.message) },
                                confirmButton = {
                                    TextButton(onClick = { viewModel.dismissCalibreSendStatus() }) { Text("OK") }
                                },
                            )
                        }
                        is CalibreSendStatus.Done -> viewModel.dismissCalibreSendStatus()
                        null -> {}
                    }
                }
            }
        }
    }

    // Merge framework (directory trigger) — see CONTRACTS.md "Merge framework"
    archiveMergeChoice?.let { choice ->
        if (choice.contentType == null) {
            MergeContentTypeChoiceDialog(
                folderName = choice.dirName,
                onDismiss = { viewModel.dismissArchiveMergeChoice() },
                onChoose = { type -> viewModel.chooseArchiveMergeContentType(type) },
            )
        } else {
            MergeProcessChoiceDialog(
                folderName = choice.dirName,
                onDismiss = { viewModel.dismissArchiveMergeChoice() },
                onChoose = { mode -> viewModel.startArchiveMergeFolderScan(mode) },
            )
        }
    }

    // Merge framework (file + directory trigger) — picker/reorder sheet
    archiveMergePickerState?.let { pickerState ->
        if (selectedArchiveMergeFlatFiles == null && selectedArchiveMergeGroups == null
            && pendingArchiveMergeFlat == null && pendingArchiveMergeGroups == null) {
            MergePackSheet(
                state = pickerState,
                onDismiss = { viewModel.dismissArchiveMergePicker() },
                onConfirmFlat = { files ->
                    pendingArchiveMergeFlat = files
                    viewModel.dismissArchiveMergePicker()
                },
                onConfirmGrouped = { groups ->
                    pendingArchiveMergeGroups = groups
                    viewModel.dismissArchiveMergePicker()
                },
            )
        }
    }

    // Destination chooser — shown when merge files are selected and assemblies exist
    val hasPendingArchiveMerge = pendingArchiveMergeFlat != null || pendingArchiveMergeGroups != null
    if (hasPendingArchiveMerge && archiveMergeDestinationAssembly == null && pendingAssemblies.isNotEmpty()) {
        fun dispatchToNew() {
            selectedArchiveMergeFlatFiles = pendingArchiveMergeFlat
            selectedArchiveMergeGroups = pendingArchiveMergeGroups
            pendingArchiveMergeFlat = null
            pendingArchiveMergeGroups = null
        }
        AlertDialog(
            onDismissRequest = { pendingArchiveMergeFlat = null; pendingArchiveMergeGroups = null },
            title = { Text("Add to...") },
            text = {
                LazyColumn {
                    item {
                        ListItem(
                            headlineContent = { Text("New request") },
                            modifier = Modifier.clickable { dispatchToNew() },
                        )
                    }
                    items(pendingAssemblies) { assembly ->
                        ListItem(
                            headlineContent = { Text(assembly.title) },
                            supportingContent = { Text(assembly.author) },
                            modifier = Modifier.clickable {
                                archiveMergeDestinationAssembly = assembly
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingArchiveMergeFlat = null; pendingArchiveMergeGroups = null }) { Text("Cancel") }
            },
        )
    }

    // Assembly-append sheet for merge → existing assembly path
    if (archiveMergeDestinationAssembly != null) {
        val assembly = archiveMergeDestinationAssembly!!
        val mergeDisplayName = when {
            pendingArchiveMergeFlat != null -> "${pendingArchiveMergeFlat!!.size} files"
            pendingArchiveMergeGroups != null -> "${pendingArchiveMergeGroups!!.size} chapters"
            else -> ""
        }
        AssemblyAppendSheet(
            formatDisplayName = mergeDisplayName,
            assembly = assembly,
            assemblyIsProtected = assembly.assemblyIsProtected(),
            isArchive = false,
            onDismiss = { archiveMergeDestinationAssembly = null; pendingArchiveMergeFlat = null; pendingArchiveMergeGroups = null },
            onConfirm = { _, _, override ->
                viewModel.appendArchiveMerge(
                    assemblyFileId = assembly.putioFileId,
                    files = pendingArchiveMergeFlat,
                    groups = pendingArchiveMergeGroups,
                    overrideTitle = override?.title,
                    overrideAuthor = override?.author,
                    overrideUuid = override?.uuid,
                    overrideTags = override?.tags,
                    overrideProtected = override?.isProtected,
                )
                archiveMergeDestinationAssembly = null
                pendingArchiveMergeFlat = null
                pendingArchiveMergeGroups = null
            },
        )
    }

    if (selectedArchiveMergeFlatFiles != null) {
        val candidates = selectedArchiveMergeFlatFiles!!
        val (initialTitle, initialAuthor) = remember(candidates) {
            MetadataUtils.extractMetadata(candidates.first().file.displayName)
        }
        CalibreConfirmationSheet(
            displayName = "${candidates.size} files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedArchiveMergeFlatFiles = null },
            onConfirm = { title, author, _, assembleBook, _, _, uuid, _, tags, isProtected ->
                viewModel.sendArchiveMerge(candidates, null, title, author, uuid, tags, isProtected, assembleBook)
                selectedArchiveMergeFlatFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
        )
    }

    if (selectedArchiveMergeGroups != null) {
        val groups = selectedArchiveMergeGroups!!
        val (initialTitle, initialAuthor) = remember(groups) {
            MetadataUtils.extractMetadata(groups.first().label)
        }
        CalibreConfirmationSheet(
            displayName = "${groups.size} chapters",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedArchiveMergeGroups = null },
            onConfirm = { title, author, _, assembleBook, _, _, uuid, _, tags, isProtected ->
                viewModel.sendArchiveMerge(null, groups, title, author, uuid, tags, isProtected, assembleBook)
                selectedArchiveMergeGroups = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
        )
    }

    // Calibre send sheet
    entryForCalibre?.let { entry ->
        val (initialTitle, initialAuthor) = remember(entry) {
            MetadataUtils.extractMetadata(entry.name)
        }
        CalibreConfirmationSheet(
            displayName = entry.name,
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { entryForCalibre = null },
            onConfirm = { title, author, _, assembleBook, _, _, uuid, _, _, _ ->
                viewModel.sendEntryToCalibre(entry, title, author, assembleBook, null, uuid)
                entryForCalibre = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
        )
    }

    // Assembly picker → target assembly dialog
    if (entryForAssembly != null && targetAssembly == null) {
        if (pendingAssemblies.isEmpty()) {
            // No pending assembly — start a new one using the regular send sheet with assembleBook=true
            val entry = entryForAssembly!!
            val (initialTitle, initialAuthor) = remember(entry) {
                MetadataUtils.extractMetadata(entry.name)
            }
            CalibreConfirmationSheet(
                displayName = entry.name,
                initialTitle = initialTitle,
                initialAuthor = initialAuthor,
                onDismiss = { entryForAssembly = null },
                onConfirm = { title, author, _, assembleBook, _, _, uuid, _, _, _ ->
                    viewModel.sendEntryToCalibre(entry, title, author, assembleBook = assembleBook, assemblyFileId = null, calibreBookUuid = uuid)
                    entryForAssembly = null
                },
                checkExists = { title, author -> viewModel.checkBookExists(title, author) },
                checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            )
        } else {
            AlertDialog(
                onDismissRequest = { entryForAssembly = null },
                title = { Text("Pick Assembly") },
                text = {
                    Column {
                        pendingAssemblies.forEach { assembly ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(assembly.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(assembly.author, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { targetAssembly = assembly },
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { entryForAssembly = null }) { Text("Cancel") }
                },
            )
        }
    }

    // Confirm append to selected assembly
    if (targetAssembly != null && entryForAssembly != null) {
        val entry = entryForAssembly!!
        val assembly = targetAssembly!!
        com.damarquez.putz.ui.files.AssemblyAppendSheet(
            formatDisplayName = entry.name,
            assembly = assembly,
            assemblyIsProtected = assembly.assemblyIsProtected(),
            isArchive = false,
            onDismiss = { targetAssembly = null; entryForAssembly = null },
            onConfirm = { _, _, override ->
                viewModel.sendEntryToCalibre(
                    entry,
                    title = assembly.title,
                    author = assembly.author,
                    assembleBook = true,
                    assemblyFileId = assembly.putioFileId,
                    calibreBookUuid = assembly.calibreBookUuid,
                    overrideTitle = override?.title,
                    overrideAuthor = override?.author,
                    overrideUuid = override?.uuid,
                    overrideTags = override?.tags,
                    overrideProtected = override?.isProtected,
                )
                targetAssembly = null
                entryForAssembly = null
            },
        )
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
    onSendToCalibre: (() -> Unit)? = null,
    onAssembleBook: (() -> Unit)? = null,
    onMerge: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
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
        } else if (!isSelectionMode && (onSendToCalibre != null || onAssembleBook != null || onMerge != null)) {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (onSendToCalibre != null) {
                        DropdownMenuItem(
                            text = { Text("Send to Calibre") },
                            onClick = { menuExpanded = false; onSendToCalibre() },
                        )
                    }
                    if (onAssembleBook != null) {
                        DropdownMenuItem(
                            text = { Text("Assemble book") },
                            onClick = { menuExpanded = false; onAssembleBook() },
                        )
                    }
                    if (onMerge != null) {
                        // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                        DropdownMenuItem(
                            text = { Text("Merge") },
                            onClick = { menuExpanded = false; onMerge() },
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
