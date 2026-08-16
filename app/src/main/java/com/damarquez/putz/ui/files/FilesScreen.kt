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
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.damarquez.putz.ui.components.ErrorView
import com.damarquez.putz.ui.components.FileItem
import com.damarquez.putz.ui.components.formatDiskSize
import com.damarquez.putz.ui.components.transientVerticalScrollbar
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration

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
import com.damarquez.putz.ui.GlobalSyncViewModel
import com.damarquez.putz.ui.files.FilesUiState
import androidx.hilt.navigation.compose.hiltViewModel

import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import com.damarquez.putz.ui.viewer.ViewerKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateToFolder: (Long, String, String?, Long, String?, String?, String?) -> Unit,
    onNavigateToFolderHighlighted: (folderId: Long, folderName: String, highlightId: Long) -> Unit,
    onNavigateToArchive: (localUri: String?, lanConnectionId: Long, lanPath: String?, archiveName: String, autoJoin: Boolean) -> Unit,
    onNavigateToPutioArchive: (fileId: Long, stubFileId: Long, fileName: String, downloadUrl: String, fileSize: Long, parentFolderId: Long, isSynced: Boolean, autoJoin: Boolean) -> Unit,
    onNavigateToViewer: (kind: ViewerKind, title: String, filePath: String) -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: FilesViewModel,
    syncViewModel: GlobalSyncViewModel,
) {
    val libraryHasUpdates by syncViewModel.libraryHasUpdates.collectAsState()

    val uiState by viewModel.uiState.collectAsState()
    val allSyncedFolderIds by viewModel.allSyncedFolderIds.collectAsState()
    val accountInfo by viewModel.accountInfo.collectAsState()
    val googleAccount by viewModel.googleAccount.collectAsState()
    val completedTransfersWithUuid by viewModel.completedTransfersWithUuid.collectAsState()
    val transferredPutioFileIds by viewModel.transferredPutioFileIds.collectAsState()
    val isGoogleSignedIn = googleAccount.isNotBlank()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val isSearchMode by viewModel.isSearchMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val nameSort by viewModel.nameSort.collectAsState()
    val dateSort by viewModel.dateSort.collectAsState()
    val sizeSort by viewModel.sizeSort.collectAsState()
    val itemCount = (uiState as? FilesUiState.Success)?.files?.size

    // Candidate pool for the join/pack pickers (PDF/EPUB/CBR/etc.): the current folder's listing
    // normally, but the search results themselves while searching, so search acts as a "virtual
    // folder" spanning whatever folders the matched files actually live in.
    val packCandidateFiles = (uiState as? FilesUiState.Success)?.let { success ->
        if (isSearchMode) success.searchResults ?: emptyList() else success.files
    } ?: emptyList()

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
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val isSelectionMode = selectedFiles.isNotEmpty()
    // CONTRACT: selection-type invariant — "regular remote" (dimmed, undownloaded) files have no
    // bulk operations in common with stubs/regular files, so a selection is either all
    // regular-remote or all not; mixing is disallowed everywhere selection is mutated below.
    val selectionIsRemoteOnly = selectedFiles.isNotEmpty() && selectedFiles.first().isRegularRemote
    var fileToDelete by remember { mutableStateOf<PutioFile?>(null) }
    var fileToRename by remember { mutableStateOf<PutioFile?>(null) }
    var fileForDetails by remember { mutableStateOf<PutioFile?>(null) }
    var fileDetailsStubContent by remember { mutableStateOf<CalibreRepository.StubContent?>(null) }
    var fileDetailsLoading by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var fileToChangeDisplayName by remember { mutableStateOf<PutioFile?>(null) }
    var displayNameValue by remember { mutableStateOf("") }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val isInHiddenScope = viewModel.isInHiddenScope

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var currentHighlightId by remember { mutableStateOf(viewModel.highlightFileId) }

    // Shared by row-tap (autoJoin=false, just browse) and the "Join archive…" menu action
    // (autoJoin=true, browse and immediately pop the merge choice dialog for the root).
    fun openArchive(file: PutioFile, autoJoin: Boolean) {
        if (file.isDeflating) return // CONTRACT: ARCHIVE_TO_FOLDER — stub mid-replacement
        if ((file.isLocal || file.isLan) && MetadataUtils.isArchive(file.displayName)) {
            onNavigateToArchive(file.localUri, file.lanConnectionId ?: -1L, file.lanPath, file.displayName, autoJoin)
        } else if (!file.isLocal && !file.isLan && file.isSynced && MetadataUtils.isArchive(file.displayName)) {
            viewModel.openPutioArchive(file, autoJoin)
        }
    }

    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchMode, isSelectionMode) {
        // The search TextField (which owns searchFocusRequester) is only in the composition
        // when isSearchMode is true AND isSelectionMode is false — see the topBar title
        // branching below, which shows a plain "N selected of M found" Text instead while a
        // selection is active. Requesting focus outside that branch throws since the
        // FocusRequester has no attached node.
        if (isSearchMode && !isSelectionMode) {
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
            viewModel.setSelectedFiles(emptySet())
        } else if (isSearchMode) {
            viewModel.toggleSearch()
        }
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
            onNavigateToPutioArchive(event.fileId, event.stubFileId, event.fileName, event.downloadUrl, event.fileSize, event.parentFolderId, event.isSynced, event.autoJoin)
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

    // Single-file Calibre send — ViewModel-backed; see FilesViewModel.calibreSingleDraft for why.
    val calibreSingleDraft by viewModel.calibreSingleDraft.collectAsState()
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

    // Text-to-PDF pack flow (RTF/TXT/HTML/DOCX/ODT, any mix)
    var textPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedTextFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // Image pack flow (PDF / EPUB / CBZ)
    var imagePackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedImageFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    var selectedImageFilesFormat by remember { mutableStateOf(ImageOutputFormat.PDF) }
    var selectedImageFilesQuality by remember { mutableStateOf<Int?>(null) }

    // CBR PDF pack flow
    var cbrPdfPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedCbrFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // CBR CBZ pack flow
    var cbrCbzPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedCbrCbzFiles by remember { mutableStateOf<List<PutioFile>?>(null) }

    // Merge framework flow (folder trigger) — see CONTRACTS.md "Merge framework"
    var selectedMergeFlatFiles by remember { mutableStateOf<List<MergeCandidateFile>?>(null) }
    var selectedMergeGroups by remember { mutableStateOf<List<MergeCandidateGroup>?>(null) }
    var mergeImageQuality by remember { mutableStateOf<Int?>(null) }
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
                is PendingDestination.Single -> viewModel.startCalibreSingleDraft(pd.file)
                is PendingDestination.Pack -> when (pd.type) {
                    "PACK" -> selectedPackFiles = pd.files
                    "PDF_PACK" -> selectedPdfFiles = pd.files
                    "EPUB_PACK" -> selectedEpubFiles = pd.files
                    "MOBI_PACK" -> selectedMobiFiles = pd.files
                    "TEXT_PDF_PACK" -> selectedTextFiles = pd.files
                    "CBR_PDF_PACK" -> selectedCbrFiles = pd.files
                    "CBR_CBZ_PACK" -> selectedCbrCbzFiles = pd.files
                    else -> { selectedImageFiles = pd.files; selectedImageFilesFormat = pd.imageFormat; selectedImageFilesQuality = pd.imageQuality }
                }
                is PendingDestination.MergeFlat -> { selectedMergeFlatFiles = pd.files; mergeImageQuality = pd.imageQuality }
                is PendingDestination.MergeGrouped -> { selectedMergeGroups = pd.groups; mergeImageQuality = pd.imageQuality }
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
                is PendingDestination.Single -> viewModel.startCalibreSingleDraft(pd.file)
                is PendingDestination.Pack -> when (pd.type) {
                    "PACK" -> selectedPackFiles = pd.files
                    "PDF_PACK" -> selectedPdfFiles = pd.files
                    "EPUB_PACK" -> selectedEpubFiles = pd.files
                    "MOBI_PACK" -> selectedMobiFiles = pd.files
                    "TEXT_PDF_PACK" -> selectedTextFiles = pd.files
                    "CBR_PDF_PACK" -> selectedCbrFiles = pd.files
                    "CBR_CBZ_PACK" -> selectedCbrCbzFiles = pd.files
                    else -> { selectedImageFiles = pd.files; selectedImageFilesFormat = pd.imageFormat; selectedImageFilesQuality = pd.imageQuality }
                }
                is PendingDestination.MergeFlat -> { selectedMergeFlatFiles = pd.files; mergeImageQuality = pd.imageQuality }
                is PendingDestination.MergeGrouped -> { selectedMergeGroups = pd.groups; mergeImageQuality = pd.imageQuality }
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

    if (calibreSingleDraft != null) {
        val draft = calibreSingleDraft!!
        val singleFile = draft.file
        // The file may come from a cached folder listing that's stale by days/weeks if this
        // folder hasn't been pull-to-refreshed — and a synced stub's put.io ID drifts every time
        // the daemon re-syncs it (see FilesViewModel.startCalibreBatchDraft for the full story).
        // Silently swap in the live current file before the user can send, so we never hand the
        // daemon a dead ID that later makes this transfer undeletable from put.io.
        LaunchedEffect(singleFile.id, singleFile.name) {
            if (singleFile.isSynced) {
                val fresh = viewModel.resolveFreshSyncedFile(singleFile)
                if (fresh != singleFile) viewModel.updateCalibreSingleDraft { it.copy(file = fresh) }
            }
        }
        val sizeProgress = rememberSizeProgress(listOf(singleFile)) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = singleFile.displayName,
            initialTitle = draft.title,
            initialAuthor = draft.author,
            initialUuid = draft.uuid,
            initialComments = draft.comments,
            initialTags = draft.tags,
            onPreview = { viewModel.previewFile(singleFile) },
            onDismiss = { viewModel.dismissCalibreSingleDraft() },
            onConfirm = { title, author, archiveMode, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendToCalibre(singleFile, title, author, archiveMode, assembleBook, isAltVersion, uuid, isProtected, convertToPdf, tags, ignoreCover = ignoreCover, comments = comments)
                viewModel.dismissCalibreSingleDraft()
            },
            onAddToChain = { title, author, archiveMode, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendToCalibre(singleFile, title, author, archiveMode, assembleBook, isAltVersion, uuid, isProtected, convertToPdf, tags, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                viewModel.dismissCalibreSingleDraft()
            },
            onDraftFieldsChanged = { title, author, uuid, comments, tags, archiveMode, assembleBook, isAltVersion, isProtected, convertToPdf, ignoreCover ->
                viewModel.updateCalibreSingleDraft {
                    it.copy(
                        title = title,
                        author = author,
                        uuid = uuid,
                        comments = comments,
                        tags = tags,
                        archiveMode = archiveMode,
                        assembleBook = assembleBook,
                        isAltVersion = isAltVersion,
                        isProtected = isProtected,
                        convertToPdf = convertToPdf,
                        ignoreCover = ignoreCover,
                    )
                }
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            checkPendingTransfer = { viewModel.findPendingTransfer(singleFile.syncedFileId, singleFile.displayName) },
            isArchive = MetadataUtils.isArchive(singleFile.displayName),
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, listOf(singleFile)),
        )
    }

    CalibreBatchDraftHost(viewModel)

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
            onConfirm = { title, author, _, _, _, matchedId, uuid, _, _, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCover(imageFile, title, author, matchedId ?: 0L, uuid)
                }
                selectedFileForCover = null
            },
            onAddToChain = { title, author, _, _, _, matchedId, uuid, _, _, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCover(imageFile, title, author, matchedId ?: 0L, uuid, addToChain = true)
                }
                selectedFileForCover = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isReplaceCover = true,
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (audiobookPackTriggerFile != null && selectedPackFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName, not name; stubs end in .sk_synced
        val audioFiles = remember(audiobookPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isMultiTrackAudio(it.displayName) }
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
        val pdfFiles = remember(pdfPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isPdf(it.displayName) }
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
            MetadataUtils.extractMetadata(packFiles.first().displayName)
        }
        val sizeProgress = rememberSizeProgress(packFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${packFiles.size} audio files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedPackFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                val fileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b"
                viewModel.sendMergeFiles("PACK", fileName, packFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedPackFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                val fileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b"
                viewModel.sendMergeFiles("PACK", fileName, packFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedPackFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, packFiles),
        )
    }

    if (selectedPdfFiles != null) {
        val pdfFiles = selectedPdfFiles!!
        val (initialTitle, initialAuthor) = remember(pdfFiles) {
            MetadataUtils.extractMetadata(pdfFiles.first().displayName)
        }
        val sizeProgress = rememberSizeProgress(pdfFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${pdfFiles.size} PDF files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedPdfFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), pdfFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedPdfFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), pdfFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedPdfFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, pdfFiles),
        )
    }

    if (epubPackTriggerFile != null && selectedEpubFiles == null && pendingDestination == null) {
        val epubFiles = remember(epubPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isEpub(it.displayName) }
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
            MetadataUtils.extractMetadata(epubFiles.first().displayName)
        }
        val sizeProgress = rememberSizeProgress(epubFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${epubFiles.size} EPUB files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedEpubFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("EPUB_PACK", applyAltVersion("Book.epub", isAltVersion), epubFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedEpubFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("EPUB_PACK", applyAltVersion("Book.epub", isAltVersion), epubFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedEpubFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, epubFiles),
        )
    }

    if (mobiPackTriggerFile != null && selectedMobiFiles == null && pendingDestination == null) {
        val mobiFiles = remember(mobiPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isMobi(it.displayName) }
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
            MetadataUtils.extractMetadata(mobiFiles.first().displayName)
        }
        val sizeProgress = rememberSizeProgress(mobiFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${mobiFiles.size} MOBI files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedMobiFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("MOBI_PACK", applyAltVersion("Book.mobi", isAltVersion), mobiFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedMobiFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("MOBI_PACK", applyAltVersion("Book.mobi", isAltVersion), mobiFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedMobiFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, mobiFiles),
        )
    }

    if (textPackTriggerFile != null && selectedTextFiles == null && pendingDestination == null) {
        val textFiles = remember(textPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isJoinableText(it.displayName) }
        }
        TextPackSheet(
            textFiles = textFiles,
            onDismiss = { textPackTriggerFile = null },
            onConfirm = { files ->
                pendingDestination = PendingDestination.Pack("TEXT_PDF_PACK", files, "Book.pdf", "${files.size} files")
                textPackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedTextFiles != null) {
        val textFiles = selectedTextFiles!!
        val (initialTitle, initialAuthor) = remember(textFiles) {
            MetadataUtils.extractMetadata(textFiles.first().displayName)
        }
        val sizeProgress = rememberSizeProgress(textFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${textFiles.size} files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedTextFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("TEXT_PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), textFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedTextFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("TEXT_PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), textFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedTextFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, textFiles),
        )
    }

    if (imagePackTriggerFile != null && selectedImageFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName; filter to image siblings in current
        // folder, or across the search results when triggered from search (see packCandidateFiles)
        val imageFiles = remember(imagePackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isImage(it.displayName) }
        }
        val defaultFormat = defaultImageOutputFormat()
        ImagePackSheet(
            imageFiles = imageFiles,
            defaultFormat = defaultFormat,
            onDismiss = { imagePackTriggerFile = null },
            onConfirm = { files, format, quality ->
                pendingDestination = PendingDestination.Pack(format.itemType, files, format.outputFileName, "${files.size} images", format, quality.quality)
                imagePackTriggerFile = null
            },
            readStubFileSize = { viewModel.readStubFileSize(it) },
        )
    }

    if (selectedImageFiles != null) {
        val imageFiles = selectedImageFiles!!
        val format = selectedImageFilesFormat
        val quality = selectedImageFilesQuality
        val (initialTitle, initialAuthor) = remember(imageFiles) {
            MetadataUtils.extractMetadata(imageFiles.first().displayName)
        }
        val sizeProgress = rememberSizeProgress(imageFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${imageFiles.size} images → ${format.outputFileName}",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedImageFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendMergeFiles(format.itemType, applyAltVersion(format.outputFileName, isAltVersion), imageFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, imageQuality = quality, comments = comments, convertToPdf = convertToPdf)
                selectedImageFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendMergeFiles(format.itemType, applyAltVersion(format.outputFileName, isAltVersion), imageFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, imageQuality = quality, comments = comments, convertToPdf = convertToPdf)
                selectedImageFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, imageFiles),
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

    var mergePickerCompression by remember { mutableStateOf(ImageCompressionLevel.ORIGINAL) }

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
            // Compression only affects the PDF-from-images engines (ImagePdfPackJob/
            // CbrPdfPackJob) — CBZ/CBR/EPUB embed source image bytes as-is by design.
            if (compressionApplicableTo(selectedFormat.itemType)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Compression",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ImageCompressionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = mergePickerCompression == level,
                            onClick = { mergePickerCompression = level },
                            label = { Text(level.label) },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
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
                    val fmt = mergeOutputFormat ?: ct.defaultOutputFormat()
                    val quality = if (compressionApplicableTo(fmt.itemType)) mergePickerCompression.quality else null
                    pendingDestination = PendingDestination.MergeFlat(ct, fmt, files, quality)
                    viewModel.dismissMergePicker()
                },
                onConfirmGrouped = { groups ->
                    val ct = activeMergeContentType ?: MergeContentType.IMAGES
                    val fmt = mergeOutputFormat ?: ct.defaultOutputFormat()
                    val quality = if (compressionApplicableTo(fmt.itemType)) mergePickerCompression.quality else null
                    pendingDestination = PendingDestination.MergeGrouped(ct, fmt, groups, quality)
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
        val sizeProgress = rememberSizeProgress(candidates.map { it.file }) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${candidates.size} ${contentType.label.lowercase()} → $effectiveOutputFileName",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedMergeFlatFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendMergeFiles(effectiveItemType, applyAltVersion(effectiveOutputFileName, isAltVersion), candidates.map { it.file }, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, imageQuality = mergeImageQuality, comments = comments, convertToPdf = convertToPdf)
                selectedMergeFlatFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendMergeFiles(effectiveItemType, applyAltVersion(effectiveOutputFileName, isAltVersion), candidates.map { it.file }, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, imageQuality = mergeImageQuality, comments = comments, convertToPdf = convertToPdf)
                selectedMergeFlatFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, candidates.map { it.file }),
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
        val allFiles = remember(groups) { groups.flatMap { it.files.map { f -> f.file } } }
        val sizeProgress = rememberSizeProgress(allFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${groups.size} chapters → $effectiveOutputFileName",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedMergeGroups = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendMergeGroups(effectiveItemType, applyAltVersion(effectiveOutputFileName, isAltVersion), groups, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, imageQuality = mergeImageQuality, comments = comments, convertToPdf = convertToPdf)
                selectedMergeGroups = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, convertToPdf, ignoreCover ->
                viewModel.sendMergeGroups(effectiveItemType, applyAltVersion(effectiveOutputFileName, isAltVersion), groups, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, imageQuality = mergeImageQuality, comments = comments, convertToPdf = convertToPdf)
                selectedMergeGroups = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, allFiles),
        )
    }

    if (cbrPdfPackTriggerFile != null && selectedCbrFiles == null && pendingDestination == null) {
        // CONTRACT: stub convention — must use displayName; filter to CBR siblings in current
        // folder, or across the search results when triggered from search (see packCandidateFiles)
        val cbrFiles = remember(cbrPdfPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isComicArchive(it.displayName) }
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
        val sizeProgress = rememberSizeProgress(cbrFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${cbrFiles.size} CBR files → PDF",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedCbrFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("CBR_PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), cbrFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedCbrFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("CBR_PDF_PACK", applyAltVersion("Book.pdf", isAltVersion), cbrFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedCbrFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, cbrFiles),
        )
    }

    if (cbrCbzPackTriggerFile != null && selectedCbrCbzFiles == null && pendingDestination == null) {
        val cbrFiles = remember(cbrCbzPackTriggerFile, packCandidateFiles) {
            packCandidateFiles.filter { MetadataUtils.isComicArchive(it.displayName) }
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
        val sizeProgress = rememberSizeProgress(cbrFiles) { viewModel.readStubFileSize(it) }
        CalibreConfirmationSheet(
            displayName = "${cbrFiles.size} CBR files → CBZ",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedCbrCbzFiles = null },
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("CBR_CBZ_PACK", applyAltVersion("Book.cbz", isAltVersion), cbrFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, comments = comments)
                selectedCbrCbzFiles = null
            },
            onAddToChain = { title, author, _, assembleBook, isAltVersion, _, uuid, comments, tags, isProtected, _, ignoreCover ->
                viewModel.sendMergeFiles("CBR_CBZ_PACK", applyAltVersion("Book.cbz", isAltVersion), cbrFiles, title, author, uuid, tags, isProtected, assembleBook, ignoreCover = ignoreCover, addToChain = true, comments = comments)
                selectedCbrCbzFiles = null
            },
            checkExists = { title, author, format -> viewModel.checkBookExists(title, author, format) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
            sizeSummary = sizeSummaryText(sizeProgress, cbrFiles),
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

    fileToChangeDisplayName?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToChangeDisplayName = null },
            title = { Text("Change display name") },
            text = {
                Column {
                    Text(
                        "Shown only in Putz — the folder isn't renamed on put.io. Leave blank to reset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = displayNameValue,
                        onValueChange = { displayNameValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.changeFolderDisplayName(file, displayNameValue.trim())
                    fileToChangeDisplayName = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { fileToChangeDisplayName = null }) { Text("Cancel") }
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
                        viewModel.setSelectedFiles(emptySet())
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

        Scaffold(
            topBar = {
                TopAppBar(
                title = {
                    if (isSearchMode && isSelectionMode) {
                        Text(
                            text = "${selectedFiles.size} selected of ${packCandidateFiles.size} found",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else if (isSearchMode) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = {
                                Text(
                                    if (!viewModel.isCloudSearchContext || searchScope == SearchScope.EVERYWHERE)
                                        "Search everywhere"
                                    else
                                        "Search in $folderName"
                                )
                            },
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
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier.clickable { viewModel.loadAccountInfo() },
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
                        isSelectionMode -> IconButton(onClick = { viewModel.setSelectedFiles(emptySet()) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                        !isRoot -> IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        // CONTRACT: selection-type invariant — "select all" only selects files of
                        // the same regular-remote-ness as the in-progress selection.
                        val selectAllCandidates = packCandidateFiles.filter { it.isRegularRemote == selectionIsRemoteOnly }
                        val isAllSelected = selectAllCandidates.isNotEmpty() &&
                            selectedFiles.size == selectAllCandidates.size
                        IconButton(
                            onClick = {
                                viewModel.setSelectedFiles(if (isAllSelected) emptySet() else selectAllCandidates.toSet())
                            },
                        ) {
                            Icon(
                                if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                contentDescription = if (isAllSelected) "Deselect all" else "Select all",
                            )
                        }
                        if (selectionIsRemoteOnly) {
                            // CONTRACT: PRIORITY_PUTIO_SYNC — the only bulk operation available
                            // for a selection of undownloaded remote files.
                            IconButton(onClick = { viewModel.requestPrioritySync(selectedFiles.toList()) }) {
                                Icon(Icons.Default.Sync, contentDescription = "Priority sync selected")
                            }
                        } else {
                            val hasFolderSelected = selectedFiles.any { it.isFolder }
                            IconButton(
                                onClick = { viewModel.startCalibreBatchDraft(selectedFiles.toList()) },
                                enabled = !hasFolderSelected,
                            ) {
                                Icon(Icons.Default.LibraryAdd, contentDescription = "Send selected to Calibre")
                            }
                            IconButton(onClick = { showBatchDeleteConfirm = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                            }
                        }
                    } else if (isSearchMode) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                        // At root, "this folder" recursively covers the whole cloud anyway —
                        // FOLDER and EVERYWHERE scope produce identical results (see search()),
                        // so the toggle has nothing to offer there.
                        if (viewModel.isCloudSearchContext && !isRoot) {
                            IconButton(onClick = { viewModel.toggleSearchScope() }) {
                                Icon(
                                    imageVector = if (searchScope == SearchScope.FOLDER) Icons.Default.Folder else Icons.Default.Public,
                                    contentDescription = if (searchScope == SearchScope.FOLDER)
                                        "Searching in $folderName — tap to search everywhere"
                                    else
                                        "Searching everywhere — tap to search in $folderName",
                                    tint = MaterialTheme.colorScheme.primary,
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
                    onRefresh = {
                        // Pulling to refresh while viewing search results must re-run the
                        // search itself, not reload the underlying folder listing — searchResults
                        // is only ever set by search(), so refreshing via loadFiles() alone left
                        // stale results (e.g. an already-deleted stub) on screen indefinitely.
                        if (isSearchMode && searchQuery.isNotEmpty()) {
                            viewModel.search(searchQuery)
                        } else {
                            viewModel.loadFiles(isRefresh = true)
                        }
                    },
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

                        TransferPreparationBanner(viewModel)

                        // Sort Toggles (shown in search mode too, so results can be sorted)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            ) {
                                if (isSearchMode && searchQuery.isNotEmpty()) {
                                    Text(
                                        text = if (state.isSearching)
                                            "${files.size} found so far…"
                                        else
                                            "${files.size} found",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

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
                                    IconButton(onClick = onNavigateToSettings) {
                                        BadgedBox(
                                            badge = {
                                                if (libraryHasUpdates) {
                                                    Badge()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .transientVerticalScrollbar(listState),
                                state = listState
                            ) {
                                items(
                                    // Guards against a duplicate id from upstream (e.g. a repeated
                                    // API entry) crashing LazyColumn with "Key already used".
                                    items = files.distinctBy { it.id },
                                    key = { it.id },
                                ) { file ->
                                    FileItem(
                                        file = file,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.setSelectedFiles(
                                                    if (file in selectedFiles)
                                                        selectedFiles - file else selectedFiles + file
                                                )
                                            } else if (file.isTrash) {
                                                onNavigateToTrash()
                                            } else if (file.isFolder) {
                                                onNavigateToFolder(
                                                    file.id,
                                                    file.displayName,
                                                    file.localUri,
                                                    file.lanConnectionId ?: -1L,
                                                    file.lanPath,
                                                    if (isInHiddenScope) "hidden" else if (file.isDrive) "DRIVE" else currentTab.name,
                                                    file.driveFileId,
                                                )
                                            } else if (file.isDrive) {
                                                viewModel.previewFile(file)
                                            } else if (file.isDeflating) {
                                                // CONTRACT: ARCHIVE_TO_FOLDER — the underlying
                                                // stub is mid-replacement; nothing to open until
                                                // the daemon finishes (see PutioFile.isDeflating).
                                            } else if (MetadataUtils.isArchive(file.displayName)) {
                                                openArchive(file, autoJoin = false)
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
                                        onLongClick = {
                                            if (!file.isDrive) viewModel.setSelectedFiles(selectedFiles + file)
                                        },
                                        // CONTRACT: bulk-select popup — only reached once already
                                        // in selection mode (FileItem shows this instead of
                                        // calling onLongClick directly in that case; see its own
                                        // combinedClickable wiring).
                                        // CONTRACT: selection-type invariant — a file whose
                                        // regular-remote-ness doesn't match the in-progress
                                        // selection is inert (no tap toggle, no long-press popup).
                                        isSelectable = !isSelectionMode || (!file.isDrive && file.isRegularRemote == selectionIsRemoteOnly),
                                        selectionCount = selectedFiles.size,
                                        onSelectNextN = {
                                            // Selects the next N items after whichever selected
                                            // item appears last in this list, where N is how many
                                            // are currently selected — so 10 selected -> next 10
                                            // (20 total) -> next 20 (40 total), etc. Lets a large
                                            // contiguous range be selected in a handful of
                                            // long-presses instead of one tap per file. Filtered to
                                            // the selection's type so it can't pull in incompatible
                                            // files (see selectionIsRemoteOnly).
                                            val n = selectedFiles.size
                                            val lastSelectedIndex = files.indexOfLast { it in selectedFiles }
                                            val nextBatch = if (lastSelectedIndex == -1) {
                                                emptyList()
                                            } else {
                                                // Filter to matching-type candidates first, THEN take N —
                                                // taking N positionally and filtering afterward (the past
                                                // bug here) let interleaved wrong-type files consume slots
                                                // in the count without being selected, so N stubs mixed
                                                // with non-stubs picked up far fewer than N stubs.
                                                files.subList((lastSelectedIndex + 1).coerceAtMost(files.size), files.size)
                                                    .filter { it.isRegularRemote == selectionIsRemoteOnly }
                                                    .take(n)
                                            }
                                            viewModel.setSelectedFiles(selectedFiles + file + nextBatch)
                                        },
                                        onUnselectAllAndSelectNextN = {
                                            // Clears the current selection entirely, then selects
                                            // the next N items starting right after wherever the
                                            // current selection already ends in this list — NOT
                                            // from wherever this long-press happened to land, which
                                            // could be anywhere within (or before) the existing
                                            // selection while scrolling back up to trigger it. This
                                            // mirrors onSelectNextN's "continue from the end of
                                            // what's already selected" behavior. N is how many were
                                            // selected before clearing. Unlike onSelectNextN (which
                                            // extends an existing selection and inherits its type),
                                            // the new selection's type is anchored to the
                                            // long-pressed file itself since the old selection no
                                            // longer exists.
                                            val n = selectedFiles.size
                                            val lastSelectedIndex = files.indexOfLast { it in selectedFiles }
                                            val startIndex = if (lastSelectedIndex == -1) files.indexOf(file) else lastSelectedIndex + 1
                                            val batch = if (startIndex == -1) {
                                                listOf(file)
                                            } else {
                                                files.subList(startIndex.coerceAtMost(files.size), files.size)
                                                    .filter { it.isRegularRemote == file.isRegularRemote }
                                                    .take(n)
                                            }
                                            viewModel.setSelectedFiles(batch.toSet())
                                        },
                                        onUnselectAllAndSelectNextNotSent = {
                                            // Clears the current selection, then walks the list
                                            // top to bottom starting right after wherever the
                                            // selection already ended (same anchor as
                                            // onUnselectAllAndSelectNextN — never wherever this
                                            // long-press happened to land), selecting the first
                                            // file that doesn't already have a calibre transfer
                                            // (any status, including FAILED, counts as "already
                                            // has one"). Lets a user who stopped partway through
                                            // a top-to-bottom send resume right where they left
                                            // off. Selects nothing (and so exits selection mode,
                                            // since isSelectionMode is derived from selectedFiles
                                            // being non-empty) if every remaining file already has
                                            // a transfer.
                                            val lastSelectedIndex = files.indexOfLast { it in selectedFiles }
                                            val startIndex = if (lastSelectedIndex == -1) files.indexOf(file) else lastSelectedIndex + 1
                                            val nextNotSent = if (startIndex == -1) {
                                                null
                                            } else {
                                                files.subList(startIndex.coerceAtMost(files.size), files.size)
                                                    .filter { it.isRegularRemote == file.isRegularRemote }
                                                    // Only stubs (isSynced) can be sent to Calibre at all —
                                                    // an unsynced regular remote file has no local_path for
                                                    // the daemon to read, so it can never have (or need) a
                                                    // calibre transfer. Restricting to stubs here also keeps
                                                    // this consistent with syncedFileId below, which is only
                                                    // meaningful for synced files.
                                                    .filter { it.isSynced }
                                                    // CONTRACT: stub convention — a synced file's own `id` is
                                                    // the stub marker file's id, not the original put.io file
                                                    // id the transfer was created against; syncedFileId is the
                                                    // one that actually matches CalibreTransferEntity.putioFileId.
                                                    .firstOrNull { it.syncedFileId !in transferredPutioFileIds }
                                            }
                                            viewModel.setSelectedFiles(setOfNotNull(nextNotSent))
                                        },
                                        onUnselectBeforeHere = {
                                            // Unselects everything above the long-pressed item in
                                            // this list, leaving the item itself and everything
                                            // after it selected.
                                            val index = files.indexOf(file)
                                            if (index > 0) {
                                                viewModel.setSelectedFiles(selectedFiles - files.subList(0, index).toSet())
                                            }
                                        },
                                        onReverseSelection = {
                                            // Flips selected/unselected for every candidate of the
                                            // same isRegularRemote type as the in-progress selection
                                            // (CONTRACT: selection-type invariant) — never crosses
                                            // into files that can't join this selection anyway.
                                            val sameTypeCandidates = packCandidateFiles.filter { it.isRegularRemote == selectionIsRemoteOnly }
                                            viewModel.setSelectedFiles(sameTypeCandidates.toSet() - selectedFiles)
                                        },
                                        onUnselectFromHere = {
                                            // Unselects the long-pressed item and everything
                                            // after it in this list, leaving only items above it
                                            // selected.
                                            val index = files.indexOf(file)
                                            viewModel.setSelectedFiles(
                                                if (index == -1) {
                                                    selectedFiles - file
                                                } else {
                                                    selectedFiles - files.subList(index, files.size).toSet()
                                                }
                                            )
                                        },
                                        onPreview = { viewModel.previewFile(it) },
                                        onReplaceCover = { selectedFileForCover = it },
                                        onSendAsImagePack = { imagePackTriggerFile = it },
                                        onSendToCalibre = { pendingDestination = PendingDestination.Single(it) },
                                        onSendAsAudiobookPack = { audiobookPackTriggerFile = it },
                                        onSendAsJoinedPdf = { pdfPackTriggerFile = it },
                                        onSendAsJoinedEpub = { epubPackTriggerFile = it },
                                        onSendAsJoinedMobi = { mobiPackTriggerFile = it },
                                        onSendAsJoinedTextPdf = { textPackTriggerFile = it },
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
                                        onMergeArchive = { archive -> openArchive(archive, autoJoin = true) },
                                        onArchiveToFolder = { archive -> viewModel.requestArchiveToFolder(archive) },
                                        hasPendingPlexAssemblies = pendingPlexAssemblies.isNotEmpty(),                                        onRequestPrioritySync = { viewModel.requestPrioritySync(it) },
                                        onDownload = { viewModel.downloadFile(it) },
                                        onCopyLink = { viewModel.copyDownloadLink(it) },
                                        onCopyJson = { viewModel.copyStubJson(it) },
                                        onDelete = { fileToDelete = file },
                                        onRename = { f ->
                                            renameValue = f.name
                                            fileToRename = f
                                        },
                                        onChangeDisplayName = { f ->
                                            displayNameValue = f.displayName
                                            fileToChangeDisplayName = f
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

/** Collects the transfer-preparation progress flows in its own small composable rather than at
 *  the top of the (huge) FilesScreen function. These tick on every file completed during a batch
 *  send (FilesViewModel.sendBatchToCalibre calls updatePrepareProgress per item) — collecting them
 *  inside FilesScreen itself forced Compose to recompose that entire function on every tick, which
 *  ART can't even JIT-compile (it exceeds the method-size limit), so each recomposition ran
 *  interpreted. A batch send running for tens of seconds meant tens of seconds of continuous
 *  full-function recomposition, compounding with any other work (e.g. editing a second batch
 *  draft) closely enough to OOM-crash the app. Scoping the read to this small composable instead
 *  means only this call site recomposes per tick. */
@Composable
private fun TransferPreparationBanner(viewModel: FilesViewModel) {
    val isPreparingTransfer by viewModel.isPreparingTransfer.collectAsState()
    if (!isPreparingTransfer) return
    val transferPreparationProgress by viewModel.transferPreparationProgress.collectAsState()
    val transferPreparationLabel by viewModel.transferPreparationLabel.collectAsState()
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
                "$transferPreparationLabel ($done/$total)"
            } ?: transferPreparationLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
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
        val imageQuality: Int? = null,
    ) : PendingDestination()
    data class MergeFlat(
        val contentType: MergeContentType,
        val outputFormat: MergeOutputFormat,
        val files: List<MergeCandidateFile>,
        val imageQuality: Int? = null,
    ) : PendingDestination()
    data class MergeGrouped(
        val contentType: MergeContentType,
        val outputFormat: MergeOutputFormat,
        val groups: List<MergeCandidateGroup>,
        val imageQuality: Int? = null,
    ) : PendingDestination()
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
