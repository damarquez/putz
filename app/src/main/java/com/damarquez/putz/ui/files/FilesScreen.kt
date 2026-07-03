package com.damarquez.putz.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.CalibreRepository
import androidx.compose.ui.text.style.TextOverflow
import com.damarquez.putz.ui.components.ErrorView
import com.damarquez.putz.ui.components.FileItem
import com.damarquez.putz.ui.navigation.Screen
import com.damarquez.putz.util.MetadataUtils

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.documentfile.provider.DocumentFile

import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.CreateNewFolder

import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import com.damarquez.putz.ui.GlobalSyncViewModel
import com.damarquez.putz.ui.files.FilesUiState
import androidx.hilt.navigation.compose.hiltViewModel

import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.NavigationRailItemDefaults
import com.damarquez.putz.ui.viewer.ViewerKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateToFolder: (Long, String, String?, Long, String?, String?) -> Unit,
    onNavigateToFolderHighlighted: (folderId: Long, folderName: String, highlightId: Long) -> Unit,
    onNavigateToArchive: (localUri: String?, lanConnectionId: Long, lanPath: String?, archiveName: String, autoFuse: Boolean) -> Unit,
    onNavigateToPutioArchive: (fileId: Long, stubFileId: Long, fileName: String, downloadUrl: String, fileSize: Long, parentFolderId: Long, isSynced: Boolean, autoFuse: Boolean) -> Unit,
    onNavigateToViewer: (kind: ViewerKind, title: String, filePath: String) -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: FilesViewModel,
) {
    val syncViewModel: GlobalSyncViewModel = hiltViewModel()
    val libraryHasUpdates by syncViewModel.libraryHasUpdates.collectAsState()

    val uiState by viewModel.uiState.collectAsState()
    val allSyncedFolderIds by viewModel.allSyncedFolderIds.collectAsState()
    val accountInfo by viewModel.accountInfo.collectAsState()
    val googleAccount by viewModel.googleAccount.collectAsState()
    val completedTransfersWithUuid by viewModel.completedTransfersWithUuid.collectAsState()
    val isGoogleSignedIn = googleAccount.isNotBlank()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val isPreparingTransfer by viewModel.isPreparingTransfer.collectAsState()
    val transferPreparationProgress by viewModel.transferPreparationProgress.collectAsState()
    val isSearchMode by viewModel.isSearchMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val nameSort by viewModel.nameSort.collectAsState()
    val dateSort by viewModel.dateSort.collectAsState()
    val sizeSort by viewModel.sizeSort.collectAsState()
    val itemCount = (uiState as? FilesUiState.Success)?.files?.size

    val context = LocalContext.current
    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = DocumentFile.fromSingleUri(context, it)?.name ?: "Unknown"
            viewModel.attachLocal(it, name, false)
        }
    }
    val pickFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = DocumentFile.fromTreeUri(context, it)?.name ?: "Unknown"
            viewModel.attachLocal(it, name, true)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<PutioFile>>(emptySet()) }
    val isSelectionMode = selectedFiles.isNotEmpty()
    var fileToDelete by remember { mutableStateOf<PutioFile?>(null) }
    var fileToRename by remember { mutableStateOf<PutioFile?>(null) }
    var fileForDetails by remember { mutableStateOf<PutioFile?>(null) }
    var fileDetailsStubContent by remember { mutableStateOf<CalibreRepository.StubContent?>(null) }
    var fileDetailsLoading by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    val isInHiddenScope = viewModel.isInHiddenScope

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var currentHighlightId by remember { mutableStateOf(viewModel.highlightFileId) }

    // Shared by row-tap (autoFuse=false, just browse) and the "Fuse archive…" menu action
    // (autoFuse=true, browse and immediately pop the merge choice dialog for the root).
    fun openArchive(file: PutioFile, autoFuse: Boolean) {
        if ((file.isLocal || file.isLan) && MetadataUtils.isArchive(file.displayName)) {
            onNavigateToArchive(file.localUri, file.lanConnectionId ?: -1L, file.lanPath, file.displayName, autoFuse)
        } else if (!file.isLocal && !file.isLan && file.isSynced && MetadataUtils.isArchive(file.displayName)) {
            viewModel.openPutioArchive(file, autoFuse)
        }
    }

    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.openParentFolderEvent.collect { (folderId, folderName, highlightId) ->
            onNavigateToFolderHighlighted(folderId, folderName, highlightId)
        }
    }

    BackHandler(enabled = isSelectionMode || isSearchMode) {
        if (isSelectionMode) {
            selectedFiles = emptySet()
        } else if (isSearchMode) {
            viewModel.toggleSearch()
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out") },
            text = { Text("Are you sure you want to sign out of Putz?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutConfirm = false
                        viewModel.signOut()
                        onSignOut()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }


    LaunchedEffect(Unit) {
        viewModel.previewIntent.collect { intent ->
            context.startActivity(intent)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.viewerEvent.collect { event ->
            onNavigateToViewer(event.kind, event.title, event.filePath)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.putioArchiveEvent.collect { event ->
            onNavigateToPutioArchive(event.fileId, event.stubFileId, event.fileName, event.downloadUrl, event.fileSize, event.parentFolderId, event.isSynced, event.autoFuse)
        }
    }

    if (uiState is FilesUiState.Success && (uiState as FilesUiState.Success).isPreviewLoading) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while loading preview */ },
            confirmButton = {},
            title = { Text("Preparing preview") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Downloading temporary file...")
                }
            }
        )
    }

    // Plex flow
    var selectedFileForPlex by remember { mutableStateOf<PutioFile?>(null) }
    var plexSelectedDestPath by remember { mutableStateOf("") }
    val plexPickerState by viewModel.plexPickerState.collectAsState()

    // Plexamp (music) flow
    var selectedFilesForPlexamp by remember { mutableStateOf<List<PutioFile>?>(null) }
    var plexampSelectedDestPath by remember { mutableStateOf("") }
    var plexampInitialAlbumName by remember { mutableStateOf("") }
    val plexampPickerState by viewModel.plexampPickerState.collectAsState()
    val plexampFolderFiles by viewModel.plexampFolderFiles.collectAsState()

    // Plex subtitle assembly flow
    val pendingPlexAssemblies by viewModel.pendingPlexAssemblies.collectAsState()
    var subtitleForAssembly by remember { mutableStateOf<PutioFile?>(null) }
    var targetPlexAssembly by remember { mutableStateOf<com.damarquez.putz.data.local.CalibreTransferEntity?>(null) }

    // "Add subtitle to existing movie" flow
    var subtitleForMovie by remember { mutableStateOf<PutioFile?>(null) }
    var selectedMovieFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedMovieFolderPath by remember { mutableStateOf("") }
    val movieBrowserState by viewModel.movieBrowserState.collectAsState()

    // Single-file Calibre send
    var selectedFileForCalibre by remember { mutableStateOf<PutioFile?>(null) }
    var selectedFileForCover by remember { mutableStateOf<PutioFile?>(null) }
    // Audiobook pack flow
    var audiobookPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedPackFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    // PDF pack flow
    var pdfPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedPdfFiles by remember { mutableStateOf<List<PutioFile>?>(null) }


    // EPUB pack flow
    var epubPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedEpubFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // MOBI pack flow
    var mobiPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedMobiFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // Image pack flow (PDF / EPUB / CBZ)
    var imagePackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedImageFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    var selectedImageFilesFormat by remember { mutableStateOf(ImageOutputFormat.PDF) }

    // CBR PDF pack flow
    var cbrPdfPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedCbrFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // CBR CBZ pack flow
    var cbrCbzPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedCbrCbzFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // Merge framework flow (folder trigger) — see CONTRACTS.md "Merge framework"
    var selectedMergeFlatFiles by remember { mutableStateOf<List<MergeCandidateFile>?>(null) }
    var selectedMergeGroups by remember { mutableStateOf<List<MergeCandidateGroup>?>(null) }
    val mergeChoiceState by viewModel.mergeChoiceState.collectAsState()
    val mergePickerState by viewModel.mergePickerState.collectAsState()

    val pendingAssemblies by viewModel.pendingAssemblies.collectAsState()
    var pendingDestination by remember { mutableStateOf<PendingDestination?>(null) }
    var destinationAssembly by remember { mutableStateOf<com.damarquez.putz.data.local.CalibreTransferEntity?>(null) }

    // Auto-forward when no pending assemblies to choose from
    LaunchedEffect(pendingDestination) {
        val pd = pendingDestination ?: return@LaunchedEffect
        if (destinationAssembly != null) return@LaunchedEffect
        if (pendingAssemblies.isEmpty()) {
            when (pd) {
                is PendingDestination.Single -> selectedFileForCalibre = pd.file
                is PendingDestination.Pack -> when (pd.type) {
                    "PACK" -> selectedPackFiles = pd.files
                    "PDF_PACK" -> selectedPdfFiles = pd.files
                    "EPUB_PACK" -> selectedEpubFiles = pd.files
                    "MOBI_PACK" -> selectedMobiFiles = pd.files
                    "CBR_PDF_PACK" -> selectedCbrFiles = pd.files
                    "CBR_CBZ_PACK" -> selectedCbrCbzFiles = pd.files
                    else -> { selectedImageFiles = pd.files; selectedImageFilesFormat = pd.imageFormat }
                }
                is PendingDestination.MergeFlat -> selectedMergeFlatFiles = pd.files
                is PendingDestination.MergeGrouped -> selectedMergeGroups = pd.groups
            }
            pendingDestination = null
        }
    }

    // Destination chooser: pick new request or an existing assembly
    if (pendingDestination != null && destinationAssembly == null && pendingAssemblies.isNotEmpty()) {
        val pd = pendingDestination!!
        val candidates = pendingAssemblies
        fun dispatchToNew() {
            when (pd) {
                is PendingDestination.Single -> selectedFileForCalibre = pd.file
                is PendingDestination.Pack -> when (pd.type) {
                    "PACK" -> selectedPackFiles = pd.files
                    "PDF_PACK" -> selectedPdfFiles = pd.files
                    "EPUB_PACK" -> selectedEpubFiles = pd.files
                    "MOBI_PACK" -> selectedMobiFiles = pd.files
                    "CBR_PDF_PACK" -> selectedCbrFiles = pd.files
                    "CBR_CBZ_PACK" -> selectedCbrCbzFiles = pd.files
                    else -> { selectedImageFiles = pd.files; selectedImageFilesFormat = pd.imageFormat }
                }
                is PendingDestination.MergeFlat -> selectedMergeFlatFiles = pd.files
                is PendingDestination.MergeGrouped -> selectedMergeGroups = pd.groups
            }
            pendingDestination = null
        }
        AlertDialog(
            onDismissRequest = { pendingDestination = null },
            title = { Text("Add to...") },
            text = {
                Column {
                    DropdownMenuItem(
                        text = { Text("New request", style = MaterialTheme.typography.bodyLarge) },
                        onClick = { dispatchToNew() },
                    )
                    if (candidates.isNotEmpty()) {
                        HorizontalDivider()
                        candidates.forEach { assembly ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(assembly.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(assembly.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { destinationAssembly = assembly },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingDestination = null }) { Text("Cancel") }
            },
        )
    }

    // AssemblyAppendSheet — shown after the user picks an existing assembly
    if (destinationAssembly != null) {
        val assembly = destinationAssembly!!
        val pd = pendingDestination
        val formatDisplayName = when (pd) {
            is PendingDestination.Single -> pd.file.displayName
            is PendingDestination.Pack -> pd.displayName
            is PendingDestination.MergeFlat -> "${pd.files.size} ${pd.contentType.label.lowercase()} → ${pd.outputFormat.outputFileName}"
            is PendingDestination.MergeGrouped -> "${pd.groups.size} chapters → ${pd.outputFormat.outputFileName}"
            null -> ""
        }
        val isArchiveFormat = pd is PendingDestination.Single && MetadataUtils.isArchive(pd.file.displayName)
        val formatSlotState = if (pd is PendingDestination.Single)
            assembly.singleFormatSlotState(pd.file.displayName)
        else FormatSlotState.Available
        AssemblyAppendSheet(
            formatDisplayName = formatDisplayName,
            assembly = assembly,
            assemblyIsProtected = assembly.assemblyIsProtected(),
            isArchive = isArchiveFormat,
            formatSlotState = formatSlotState,
            onDismiss = { destinationAssembly = null; pendingDestination = null },
            onConfirm = { isAltVersion, archiveMode, override ->
                if (pd == null) return@AssemblyAppendSheet
                val overrideTitle = override?.title
                val overrideAuthor = override?.author
                val overrideUuid = override?.uuid
                val overrideTags = override?.tags
                val overrideProtected = override?.isProtected
                when (pd) {
                    is PendingDestination.Single -> viewModel.appendToAssembly(
                        assembly.putioFileId, pd.file, archiveMode, isAltVersion,
                        overrideTitle, overrideAuthor, overrideUuid, overrideTags, overrideProtected,
                    )
                    is PendingDestination.Pack -> {
                        val fileName = applyAltVersion(pd.outputFileName, isAltVersion)
                        viewModel.appendMergeToAssembly(
                            assembly.putioFileId, pd.type, fileName, files = pd.files,
                            overrideTitle = overrideTitle, overrideAuthor = overrideAuthor,
                            overrideUuid = overrideUuid, overrideTags = overrideTags, overrideProtected = overrideProtected,
                        )
                    }
                    is PendingDestination.MergeFlat -> {
                        viewModel.appendMergeToAssembly(
                            assembly.putioFileId, pd.outputFormat.itemType, applyAltVersion(pd.outputFormat.outputFileName, isAltVersion),
                            files = pd.files.map { it.file },
                            overrideTitle = overrideTitle, overrideAuthor = overrideAuthor,
                            overrideUuid = overrideUuid, overrideTags = overrideTags, overrideProtected = overrideProtected,
                        )
                    }
                    is PendingDestination.MergeGrouped -> {
                        viewModel.appendMergeToAssembly(
                            assembly.putioFileId, pd.outputFormat.itemType, applyAltVersion(pd.outputFormat.outputFileName, isAltVersion),
                            groups = pd.groups,
                            overrideTitle = overrideTitle, overrideAuthor = overrideAuthor,
                            overrideUuid = overrideUuid, overrideTags = overrideTags, overrideProtected = overrideProtected,
                        )
                    }
                }
                destinationAssembly = null
                pendingDestination = null
            },
        )
    }

    val isRoot = viewModel.parentId == 0L
    val folderName = viewModel.folderName

    if (selectedFileForCalibre != null) {
        val singleFile = selectedFileForCalibre!!
        val (initialTitle, initialAuthor) = remember(singleFile) {
            MetadataUtils.extractMetadata(singleFile.displayName)
        }
        CalibreConfirmationSheet(
            displayName = singleFile.displayName,
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedFileForCalibre = null },
            onConfirm = { title, author, archiveMode, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendToCalibre(singleFile, title, author, archiveMode, assembleBook, isAltVersion, uuid, isProtected, tags)
                selectedFileForCalibre = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isArchive = MetadataUtils.isArchive(singleFile.displayName),
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (selectedFileForCover != null) {
        val imageFile = selectedFileForCover!!
        val (initialTitle, initialAuthor) = remember(imageFile) {
            MetadataUtils.extractMetadata(imageFile.displayName)
        }
        CalibreConfirmationSheet(
            displayName = imageFile.displayName,
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedFileForCover = null },
            onConfirm = { title, author, _, _, _, matchedId, uuid, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCover(imageFile, title, author, matchedId ?: 0L, uuid)
                }
                selectedFileForCover = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isReplaceCover = true,
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (audiobookPackTriggerFile != null && selectedPackFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName, not name; stubs end in .sk_synced
        val audioFiles = remember(audiobookPackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isMultiTrackAudio(it.displayName) }
                ?: emptyList()
        }
        AudiobookPackSheet(
            audioFiles = audioFiles,
            onDismiss = { audiobookPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("PACK", files, "Audiobook.m4b", "${files.size} audio files")
                audiobookPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (pdfPackTriggerFile != null && selectedPdfFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName, not name; stubs end in .sk_synced
        val pdfFiles = remember(pdfPackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isPdf(it.displayName) }
                ?: emptyList()
        }
        PdfPackSheet(
            pdfFiles = pdfFiles,
            onDismiss = { pdfPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("PDF_PACK", files, "Book.pdf", "${files.size} PDF files")
                pdfPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedPackFiles != null) {
        val packFiles = selectedPackFiles!!
        val (initialTitle, initialAuthor) = remember(packFiles) {
            MetadataUtils.extractMetadata(packFiles.first().name)
        }
        CalibreConfirmationSheet(
            displayName = "${packFiles.size} audio files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedPackFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                val fileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b"
                viewModel.sendMergeFiles("PACK", fileName, packFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedPackFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (selectedPdfFiles != null) {
        val pdfFiles = selectedPdfFiles!!
        val (initialTitle, initialAuthor) = remember(pdfFiles) {
            MetadataUtils.extractMetadata(pdfFiles.first().name)
        }
        CalibreConfirmationSheet(
            displayName = "${pdfFiles.size} PDF files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedPdfFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles("PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), pdfFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedPdfFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (epubPackTriggerFile != null && selectedEpubFiles == null && pendingDestination == null) {
        val epubFiles = remember(epubPackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isEpub(it.displayName) }
                ?: emptyList()
        }
        EpubPackSheet(
            epubFiles = epubFiles,
            onDismiss = { epubPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("EPUB_PACK", files, "Book.epub", "${files.size} EPUB files")
                epubPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedEpubFiles != null) {
        val epubFiles = selectedEpubFiles!!
        val (initialTitle, initialAuthor) = remember(epubFiles) {
            MetadataUtils.extractMetadata(epubFiles.first().name)
        }
        CalibreConfirmationSheet(
            displayName = "${epubFiles.size} EPUB files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedEpubFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles("EPUB_PACK", applyAltVersion("Book.epub", isAltVersion), epubFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedEpubFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (mobiPackTriggerFile != null && selectedMobiFiles == null && pendingDestination == null) {
        val mobiFiles = remember(mobiPackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isMobi(it.displayName) }
                ?: emptyList()
        }
        MobiPackSheet(
            mobiFiles = mobiFiles,
            onDismiss = { mobiPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("MOBI_PACK", files, "Book.mobi", "${files.size} MOBI files")
                mobiPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedMobiFiles != null) {
        val mobiFiles = selectedMobiFiles!!
        val (initialTitle, initialAuthor) = remember(mobiFiles) {
            MetadataUtils.extractMetadata(mobiFiles.first().name)
        }
        CalibreConfirmationSheet(
            displayName = "${mobiFiles.size} MOBI files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedMobiFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles("MOBI_PACK", applyAltVersion("Book.mobi", isAltVersion), mobiFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedMobiFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (imagePackTriggerFile != null && selectedImageFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName; filter to image siblings in current folder
        val imageFiles = remember(imagePackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isImage(it.displayName) }
                ?: emptyList()
        }
        val defaultFormat = remember(imageFiles) { defaultImageOutputFormat(imageFiles.map { it.displayName }) }
        ImagePackSheet(
            imageFiles = imageFiles,
            defaultFormat = defaultFormat,
            onDismiss = { imagePackTriggerFile = null },
            onConfirm = { files, format ->
                pendingDestination = PendingDestination.Pack(format.itemType, files, format.outputFileName, "${files.size} images", format)
                imagePackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedImageFiles != null) {
        val imageFiles = selectedImageFiles!!
        val format = selectedImageFilesFormat
        val (initialTitle, initialAuthor) = remember(imageFiles) {
            MetadataUtils.extractMetadata(imageFiles.first().displayName)
        }
        CalibreConfirmationSheet(
            displayName = "${imageFiles.size} images → ${format.outputFileName}",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedImageFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles(format.itemType, applyAltVersion(format.outputFileName, isAltVersion), imageFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedImageFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    // Merge framework (folder trigger) — see CONTRACTS.md "Merge framework"
    val activeMergeContentType by viewModel.activeMergeContentType.collectAsState()
    var mergeOutputFormat by remember { mutableStateOf<MergeOutputFormat?>(null) }

    // When a folder scan completes, re-derive the default output format for the active content
    // type (e.g. images default to CBZ if any GIFs are present, else the type's first option).
    LaunchedEffect(mergePickerState) {
        val ct = activeMergeContentType ?: return@LaunchedEffect
        mergeOutputFormat = when (val s = mergePickerState) {
            is MergePickerState.ReadyFlat -> ct.defaultOutputFormat(s.files.map { it.file.displayName })
            is MergePickerState.ReadyGrouped -> ct.defaultOutputFormat(s.groups.flatMap { it.files }.map { it.file.displayName })
            else -> mergeOutputFormat
        }
    }

    when (val state = mergeChoiceState) {
        is MergeChoiceState.Scanning -> MergeScanningDialog(state.folderName) { viewModel.dismissMergeProcessChoice() }
        is MergeChoiceState.Error -> MergeScanErrorDialog(state.folderName, state.message) { viewModel.dismissMergeProcessChoice() }
        is MergeChoiceState.Ready -> {
            if (state.contentType == null) {
                MergeContentTypeChoiceDialog(
                    folderName = state.folderName,
                    scan = state.scan,
                    onDismiss = { viewModel.dismissMergeProcessChoice() },
                    onChoose = { type -> viewModel.chooseMergeContentType(type) },
                )
            } else {
                MergeProcessChoiceDialog(
                    folderName = state.folderName,
                    onDismiss = { viewModel.dismissMergeProcessChoice() },
                    onChoose = { mode -> viewModel.startMergeFolderScan(mode) },
                )
            }
        }
        null -> {}
    }

    val outputFormatPicker: (@Composable () -> Unit)? = activeMergeContentType?.let { ct ->
        val options = ct.outputFormatOptions()
        val selectedFormat = mergeOutputFormat ?: ct.defaultOutputFormat()
        ({
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Output format",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                options.forEach { fmt ->
                    FilterChip(
                        selected = selectedFormat == fmt,
                        onClick = { mergeOutputFormat = fmt },
                        label = { Text(fmt.label) },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        })
    }

    mergePickerState?.let { pickerState ->
        if (selectedMergeFlatFiles == null && selectedMergeGroups == null) {
            MergePackSheet(
                state = pickerState,
                onDismiss = { viewModel.dismissMergePicker() },
                onConfirmFlat = { files ->
                    val ct = activeMergeContentType ?: MergeContentType.IMAGES
                    pendingDestination = PendingDestination.MergeFlat(ct, mergeOutputFormat ?: ct.defaultOutputFormat(), files)
                    viewModel.dismissMergePicker()
                },
                onConfirmGrouped = { groups ->
                    val ct = activeMergeContentType ?: MergeContentType.IMAGES
                    pendingDestination = PendingDestination.MergeGrouped(ct, mergeOutputFormat ?: ct.defaultOutputFormat(), groups)
                    viewModel.dismissMergePicker()
                },
                readStubFileSize = { viewModel.readStubFileSize(it) },
                extraControls = outputFormatPicker,
            )
        }
    }

    if (selectedMergeFlatFiles != null) {
        val candidates = selectedMergeFlatFiles!!
        val contentType = activeMergeContentType ?: MergeContentType.IMAGES
        val format = mergeOutputFormat ?: contentType.defaultOutputFormat()
        val effectiveItemType = format.itemType
        val effectiveOutputFileName = format.outputFileName
        val (initialTitle, initialAuthor) = remember(candidates) {
            MetadataUtils.extractMetadata(candidates.first().file.displayName)
        }
        CalibreConfirmationSheet(
            displayName = "${candidates.size} ${contentType.label.lowercase()} → $effectiveOutputFileName",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedMergeFlatFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles(effectiveItemType, applyAltVersion(effectiveOutputFileName, isAltVersion), candidates.map { it.file }, title, author, uuid, tags, isProtected, assembleBook)
                selectedMergeFlatFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (selectedMergeGroups != null) {
        val groups = selectedMergeGroups!!
        val contentType = activeMergeContentType ?: MergeContentType.IMAGES
        val format = mergeOutputFormat ?: contentType.defaultOutputFormat()
        val effectiveItemType = format.itemType
        val effectiveOutputFileName = format.outputFileName
        val (initialTitle, initialAuthor) = remember(groups) {
            MetadataUtils.extractMetadata(groups.first().label)
        }
        CalibreConfirmationSheet(
            displayName = "${groups.size} chapters → $effectiveOutputFileName",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedMergeGroups = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeGroups(effectiveItemType, applyAltVersion(effectiveOutputFileName, isAltVersion), groups, title, author, uuid, tags, isProtected, assembleBook)
                selectedMergeGroups = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (cbrPdfPackTriggerFile != null && selectedCbrFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName; filter to CBR siblings in current folder
        val cbrFiles = remember(cbrPdfPackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isComicArchive(it.displayName) }
                ?: emptyList()
        }
        CbrPdfPackSheet(
            cbrFiles = cbrFiles,
            onDismiss = { cbrPdfPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("CBR_PDF_PACK", files, "Book.pdf", "${files.size} CBR files")
                cbrPdfPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedCbrFiles != null) {
        val cbrFiles = selectedCbrFiles!!
        val (initialTitle, initialAuthor) = remember(cbrFiles) {
            MetadataUtils.extractMetadata(cbrFiles.first().displayName)
        }
        CalibreConfirmationSheet(
            displayName = "${cbrFiles.size} CBR files → PDF",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedCbrFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles("CBR_PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), cbrFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedCbrFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (cbrCbzPackTriggerFile != null && selectedCbrCbzFiles == null && pendingDestination == null) {
        val cbrFiles = remember(cbrCbzPackTriggerFile) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isComicArchive(it.displayName) }
                ?: emptyList()
        }
        CbrPdfPackSheet(
            cbrFiles = cbrFiles,
            outputLabel = "CBZ",
            onDismiss = { cbrCbzPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("CBR_CBZ_PACK", files, "Book.cbz", "${files.size} CBR files")
                cbrCbzPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedCbrCbzFiles != null) {
        val cbrFiles = selectedCbrCbzFiles!!
        val (initialTitle, initialAuthor) = remember(cbrFiles) {
            MetadataUtils.extractMetadata(cbrFiles.first().displayName)
        }
        CalibreConfirmationSheet(
            displayName = "${cbrFiles.size} CBR files → CBZ",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedCbrCbzFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, isProtected ->
                viewModel.sendMergeFiles("CBR_CBZ_PACK", applyAltVersion("Book.cbz", isAltVersion), cbrFiles, title, author, uuid, tags, isProtected, assembleBook)
                selectedCbrCbzFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (selectedFileForPlex != null) {
        val plexFile = selectedFileForPlex!!
        val (initialTitle, initialYear) = remember(plexFile) {
            MetadataUtils.parseMovieTitleAndYear(plexFile.displayName)
        }
        PlexConfirmationSheet(
            displayName = plexFile.displayName,
            initialTitle = initialTitle,
            initialYear = initialYear,
            selectedDestPath = plexSelectedDestPath,
            onDismiss = {
                selectedFileForPlex = null
                plexSelectedDestPath = ""
            },
            onBrowse = { viewModel.openPlexFolderPicker() },
            onConfirm = { title, year, destPath, assembleMode, createFolder ->
                viewModel.sendToPlex(plexFile, title, year, destPath, assembleMode, createFolder)
                selectedFileForPlex = null
                plexSelectedDestPath = ""
            },
        )
    }

    plexPickerState?.let { pickerState ->
        PlexFolderPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.dismissPlexPicker() },
            onNavigateUp = { viewModel.plexPickerNavigateUp() },
            onNavigateInto = { folder -> viewModel.browsePlexFolder(folder) },
            onSelect = { relativePath ->
                plexSelectedDestPath = relativePath
                viewModel.dismissPlexPicker()
            },
        )
    }

    // Consume folder scan results from the ViewModel
    LaunchedEffect(plexampFolderFiles) {
        plexampFolderFiles?.let { (albumHint, files) ->
            plexampInitialAlbumName = albumHint
            plexampSelectedDestPath = ""
            selectedFilesForPlexamp = files
            viewModel.dismissPlexampFolderFiles()
        }
    }

    selectedFilesForPlexamp?.let { files ->
        val displayName = if (files.size == 1) files.first().displayName else "${files.size} audio files"
        PlexampConfirmationSheet(
            displayName = displayName,
            initialArtistName = "",
            initialAlbumName = plexampInitialAlbumName,
            selectedDestPath = plexampSelectedDestPath,
            onDismiss = {
                selectedFilesForPlexamp = null
                plexampSelectedDestPath = ""
                plexampInitialAlbumName = ""
            },
            onBrowse = { viewModel.openPlexampFolderPicker() },
            onConfirm = { artistName, albumName, destPath, createFolder ->
                viewModel.sendToPlexamp(files, artistName, albumName, createFolder, destPath)
                selectedFilesForPlexamp = null
                plexampSelectedDestPath = ""
                plexampInitialAlbumName = ""
            },
        )
    }

    plexampPickerState?.let { pickerState ->
        PlexFolderPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.dismissPlexampPicker() },
            onNavigateUp = { viewModel.plexampPickerNavigateUp() },
            onNavigateInto = { folder -> viewModel.browsePlexampFolder(folder) },
            onSelect = { relativePath ->
                plexampSelectedDestPath = relativePath
                viewModel.dismissPlexampPicker()
            },
        )
    }


    // Plex assembly picker for subtitles
    if (subtitleForAssembly != null && targetPlexAssembly == null) {
        AlertDialog(
            onDismissRequest = { subtitleForAssembly = null },
            title = { Text("Add to movie assembly") },
            text = {
                Column {
                    if (pendingPlexAssemblies.isEmpty()) {
                        Text("No pending movie assemblies found.")
                    } else {
                        pendingPlexAssemblies.forEach { assembly ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(assembly.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(assembly.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { targetPlexAssembly = assembly }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { subtitleForAssembly = null }) { Text("Cancel") }
            }
        )
    }

    if (subtitleForAssembly != null && targetPlexAssembly != null) {
        val assembly = targetPlexAssembly!!
        val usedLanguages = remember(assembly.batchData) {
            try {
                com.damarquez.putz.data.repository.PlexBatchData.fromJson(assembly.batchData ?: "")
                    ?.items?.filter { it.item_type == "SUBTITLE" }?.mapNotNull { it.language }?.toSet()
                    ?: emptySet()
            } catch (_: Exception) { emptySet() }
        }
        PlexLanguagePicker(
            disabledLanguages = usedLanguages,
            onDismiss = {
                subtitleForAssembly = null
                targetPlexAssembly = null
            },
            onConfirm = { lang ->
                viewModel.appendSubtitleToPlexAssembly(assembly.putioFileId, subtitleForAssembly!!, lang)
                subtitleForAssembly = null
                targetPlexAssembly = null
            },
        )
    }

    // "Add subtitle to movie" browser
    movieBrowserState?.let { browserState ->
        PlexFolderPickerSheet(
            state = browserState,
            onDismiss = { viewModel.dismissMovieBrowser() },
            onNavigateUp = { viewModel.movieBrowserNavigateUp() },
            onNavigateInto = { folder -> viewModel.browseMovieBrowserFolder(folder) },
            onSelect = { _ -> },
            onFileSelected = { file ->
                val folderPath = browserState.currentPath
                    .let { if (browserState.rootPath.isEmpty()) it else it.removePrefix(browserState.rootPath).trimStart('/') }
                selectedMovieFile = file
                selectedMovieFolderPath = folderPath
                viewModel.dismissMovieBrowser()
            },
        )
    }

    if (subtitleForMovie != null && selectedMovieFile != null) {
        PlexLanguagePicker(
            title = "Subtitle language for ${selectedMovieFile!!.name}",
            onDismiss = {
                subtitleForMovie = null
                selectedMovieFile = null
                selectedMovieFolderPath = ""
            },
            onConfirm = { lang ->
                viewModel.sendAddSubtitleToMovie(subtitleForMovie!!, lang, selectedMovieFolderPath, selectedMovieFile!!.name)
                subtitleForMovie = null
                selectedMovieFile = null
                selectedMovieFolderPath = ""
            },
        )
    }

    fileToRename?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Rename") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameValue.isNotBlank()) viewModel.renameFile(file, renameValue.trim())
                    fileToRename = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) { Text("Cancel") }
            },
        )
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("${if (file.isLocal) "Detach" else "Delete"} \"${file.displayName}\"?") },
            text = {
                Text(
                    if (file.isLocal) "This local attachment will be removed from Putz. Your original file will not be touched."
                    else if (file.isSynced) "The put.io stub will be deleted. The local copy will be moved to the trashcan folder."
                    else if (file.isFolder) "This folder and all its contents will be permanently deleted from put.io."
                    else "This file will be permanently deleted from put.io.\n\nIf the daemon is currently downloading it to the local mirror, the download will fail and may leave an incomplete file on disk."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFiles(listOf(file))
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(if (file.isLocal) "Detach" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            },
        )
    }

    fileForDetails?.let { file ->
        AlertDialog(
            onDismissRequest = { fileForDetails = null },
            title = { Text("File details") },
            text = {
                Column {
                    @Composable
                    fun DetailRow(label: String, value: String) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    DetailRow("Name", file.displayName)
                    if (fileDetailsLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading stub details...", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        val localPath = fileDetailsStubContent?.local_path
                        val originalSize = fileDetailsStubContent?.file_size
                        DetailRow("Local path", localPath ?: "Unavailable")
                        DetailRow("Original size", originalSize?.let { formatFileSize(it) } ?: "Unavailable")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { fileForDetails = null }) { Text("Close") }
            },
        )
    }

    if (showBatchDeleteConfirm) {
        val hasLocal = selectedFiles.any { it.isLocal }
        val hasRemote = selectedFiles.any { !it.isLocal }
        val hasSynced = selectedFiles.any { it.isSynced }
        val actionText = when {
            hasLocal && hasRemote -> "Detach/Delete"
            hasLocal -> "Detach"
            else -> "Delete"
        }

        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("$actionText ${selectedFiles.size} items?") },
            text = { Text("Selected items will be removed from Putz/put.io." + if (hasSynced) " Local copies of synced files will be moved to the trashcan folder." else "") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFiles(selectedFiles.toList())
                        selectedFiles = emptySet()
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(actionText) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.width(54.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            val railColors = NavigationRailItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicatorColor = Color.Transparent,
            )
            Spacer(modifier = Modifier.height(16.dp))
            NavigationRailItem(
                selected = currentTab == FilesTab.CLOUD,
                onClick = {
                    if (viewModel.parentId != 0L) {
                        onNavigateToFolder(0L, "Your Files", null, -1L, null, FilesTab.CLOUD.name)
                    } else {
                        viewModel.setTab(FilesTab.CLOUD)
                    }
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (currentTab == FilesTab.CLOUD) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = "Cloud")
                    }
                },
                colors = railColors,
            )
            NavigationRailItem(
                selected = currentTab == FilesTab.SPECIAL,
                onClick = {
                    if (viewModel.parentId != 0L) {
                        onNavigateToFolder(0L, "Your Files", null, -1L, null, FilesTab.SPECIAL.name)
                    } else {
                        viewModel.setTab(FilesTab.SPECIAL)
                    }
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (currentTab == FilesTab.SPECIAL) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = "Special Folders")
                    }
                },
                colors = railColors,
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                title = {
                    if (isSearchMode) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = { Text("Search in $folderName") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else if (isSelectionMode) {
                        Text(
                            text = "${selectedFiles.size} selected",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        Column {
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (isRoot) {
                                accountInfo?.let { info ->
                                    val subtitle = when {
                                        info.diskQuota > 0 ->
                                            "${info.username} (${formatDiskSize(info.diskAvail)} of ${formatDiskSize(info.diskQuota)} free)"
                                        info.diskUsed > 0 ->
                                            "${info.username} (${formatDiskSize(info.diskUsed)} used, quota unknown)"
                                        else -> info.username
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else if (itemCount != null) {
                                Text(
                                    text = "$itemCount item${if (itemCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        isSearchMode -> IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                        isSelectionMode -> IconButton(onClick = { selectedFiles = emptySet() }) {
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
                    } else if (isSearchMode) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
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
        floatingActionButton = {
            if (viewModel.parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID) {
                Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(
                        onClick = { pickFolderLauncher.launch(null) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Attach folder")
                    }
                    FloatingActionButton(
                        onClick = { pickFileLauncher.launch(arrayOf("*/*")) },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Attach file")
                    }
                }
            }
        },
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
                val files = if (isSearchMode) {
                    state.searchResults ?: emptyList()
                } else {
                    state.files
                }

                PullToRefreshBox(
                    isRefreshing = state.isRefreshing || state.isSearching,
                    onRefresh = { viewModel.loadFiles(isRefresh = true) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    Column {
                        if (state.isScanning) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        if (isPreparingTransfer) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = transferPreparationProgress?.let { (done, total) ->
                                        "Preparing files for Calibre… ($done/$total)"
                                    } ?: "Preparing files for Calibre…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }

                        // Sort Toggles (shown in search mode too, so results can be sorted)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            ) {
                                // Name Sort
                                IconButton(onClick = { viewModel.toggleNameSort() }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.SortByAlpha,
                                            contentDescription = "Sort by name",
                                            tint = if (nameSort != SortOrder.NONE) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (nameSort != SortOrder.NONE) {
                                            Icon(
                                                imageVector = if (nameSort == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Date Sort
                                IconButton(onClick = { viewModel.toggleDateSort() }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.CalendarToday,
                                            contentDescription = "Sort by date",
                                            tint = if (dateSort != SortOrder.NONE) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (dateSort != SortOrder.NONE) {
                                            Icon(
                                                imageVector = if (dateSort == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Size Sort
                                IconButton(onClick = { viewModel.toggleSizeSort() }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.DataUsage,
                                            contentDescription = "Sort by size",
                                            tint = if (sizeSort != SortOrder.NONE) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (sizeSort != SortOrder.NONE) {
                                            Icon(
                                                imageVector = if (sizeSort == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                if (!isSearchMode) {
                                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                                    IconButton(onClick = { viewModel.toggleSearch() }) {
                                        Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.loadFiles(isRefresh = true) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Box {
                                        IconButton(onClick = { showMenu = true }) {
                                            BadgedBox(
                                                badge = {
                                                    if (libraryHasUpdates) {
                                                        Badge()
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
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
                                                    showSignOutConfirm = true
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                        if (files.isEmpty()) {
                            if (isSearchMode && searchQuery.isNotEmpty() && !state.isSearching) {
                                NoResultsView(
                                    query = searchQuery,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (!isSearchMode && !state.isScanning) {
                                EmptyFolderView(
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // Loading or Scanning
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = listState
                            ) {
                                items(
                                    items = files,
                                    key = { it.id },
                                ) { file ->
                                    FileItem(
                                        file = file,
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedFiles = if (file in selectedFiles)
                                                    selectedFiles - file else selectedFiles + file
                                            } else if (file.isTrash) {
                                                onNavigateToTrash()
                                            } else if (file.isFolder) {
                                                onNavigateToFolder(
                                                    file.id,
                                                    file.name,
                                                    file.localUri,
                                                    file.lanConnectionId ?: -1L,
                                                    file.lanPath,
                                                    if (isInHiddenScope) "hidden" else currentTab.name,
                                                )
                                            } else if (MetadataUtils.isArchive(file.displayName)) {
                                                openArchive(file, autoFuse = false)
                                            } else if (!file.isLocal && !file.isLan && file.isSynced) {
                                                fileForDetails = file
                                                fileDetailsStubContent = null
                                                fileDetailsLoading = true
                                                scope.launch {
                                                    fileDetailsStubContent = viewModel.readStubContent(file)
                                                    fileDetailsLoading = false
                                                }
                                            }
                                        },
                                        onLongClick = { selectedFiles = selectedFiles + file },
                                        onPreview = { viewModel.previewFile(it) },
                                        onReplaceCover = { selectedFileForCover = it },
                                        onSendAsImagePack = { imagePackTriggerFile = it },
                                        onSendToCalibre = { pendingDestination = PendingDestination.Single(it) },
                                        onSendAsAudiobookPack = { audiobookPackTriggerFile = it },
                                        onSendAsJoinedPdf = { pdfPackTriggerFile = it },
                                        onSendAsJoinedEpub = { epubPackTriggerFile = it },
                                        onSendAsJoinedMobi = { mobiPackTriggerFile = it },
                                        onSendAsCbrPdf = { cbrPdfPackTriggerFile = it },
                                        onSendAsCbrCbz = { cbrCbzPackTriggerFile = it },
                                        onSendToPlex = {
                                            plexSelectedDestPath = ""
                                            selectedFileForPlex = it
                                        },
                                        onAssembleSubtitleIntoPlex = { subtitleForAssembly = it },
                                        onAddSubtitleToMovie = {
                                            subtitleForMovie = it
                                            selectedMovieFile = null
                                            selectedMovieFolderPath = ""
                                            viewModel.openMovieBrowser()
                                        },
                                        onSendToPlexamp = { target ->
                                            if (target.isFolder) {
                                                viewModel.scanFolderForPlexamp(target)
                                            } else {
                                                plexampInitialAlbumName = ""
                                                plexampSelectedDestPath = ""
                                                selectedFilesForPlexamp = listOf(target)
                                            }
                                        },
                                        onMergeFolder = { folder -> viewModel.openMergeProcessChoice(folder) },
                                        onMergeArchive = { archive -> openArchive(archive, autoFuse = true) },
                                        hasPendingPlexAssemblies = pendingPlexAssemblies.isNotEmpty(),                                        onRequestPrioritySync = { viewModel.requestPrioritySync(it) },
                                        onDownload = { viewModel.downloadFile(it) },
                                        onCopyLink = { viewModel.copyDownloadLink(it) },
                                        onDelete = { fileToDelete = file },
                                        onRename = { f ->
                                            renameValue = f.name
                                            fileToRename = f
                                        },
                                        isInHiddenFolder = isInHiddenScope && !file.isFolder,
                                        isSelected = file in selectedFiles,
                                        isSelectionMode = isSelectionMode,
                                        isGoogleSignedIn = isGoogleSignedIn,
                                        isHighlighted = file.id == currentHighlightId,
                                        isFolderAllSynced = file.isFolder && file.id in allSyncedFolderIds,
                                        isInSearchResults = isSearchMode,
                                        onOpenParentFolder = { viewModel.openParentFolder(file) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun NoResultsView(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = "No results for \"$query\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun applyAltVersion(fileName: String, isAltVersion: Boolean) =
    if (isAltVersion) "${fileName}_bkp" else fileName

private sealed class PendingDestination {
    data class Single(val file: com.damarquez.putz.data.model.PutioFile) : PendingDestination()
    data class Pack(
        val type: String,
        val files: List<com.damarquez.putz.data.model.PutioFile>,
        val outputFileName: String,
        val displayName: String,
        val imageFormat: ImageOutputFormat = ImageOutputFormat.PDF,
    ) : PendingDestination()
    data class MergeFlat(
        val contentType: MergeContentType,
        val outputFormat: MergeOutputFormat,
        val files: List<MergeCandidateFile>,
    ) : PendingDestination()
    data class MergeGrouped(
        val contentType: MergeContentType,
        val outputFormat: MergeOutputFormat,
        val groups: List<MergeCandidateGroup>,
    ) : PendingDestination()
}

private fun formatDiskSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
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

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
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
