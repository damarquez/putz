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
import androidx.compose.material3.rememberModalBottomSheetState
import com.damarquez.putz.data.model.PutioFile
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.NavigationRailItemDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateToFolder: (Long, String, String?, Long, String?, String?) -> Unit,
    onNavigateToFolderHighlighted: (folderId: Long, folderName: String, highlightId: Long) -> Unit,
    onNavigateToArchive: (localUri: String?, lanConnectionId: Long, lanPath: String?, archiveName: String) -> Unit,
    onNavigateToPutioArchive: (fileId: Long, stubFileId: Long, fileName: String, downloadUrl: String, fileSize: Long, parentFolderId: Long, isSynced: Boolean) -> Unit,
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
    val isSearchMode by viewModel.isSearchMode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val nameSort by viewModel.nameSort.collectAsState()
    val dateSort by viewModel.dateSort.collectAsState()
    
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
    var renameValue by remember { mutableStateOf("") }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    val isInHiddenScope = viewModel.isInHiddenScope

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var currentHighlightId by remember { mutableStateOf(viewModel.highlightFileId) }

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
        viewModel.putioArchiveEvent.collect { event ->
            onNavigateToPutioArchive(event.fileId, event.stubFileId, event.fileName, event.downloadUrl, event.fileSize, event.parentFolderId, event.isSynced)
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
    val audiobookPackSheetState = rememberModalBottomSheetState()

    // PDF pack flow
    var pdfPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedPdfFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    var selectedPdfFilesForAssembly by remember { mutableStateOf<List<PutioFile>?>(null) }
    var isPdfPackAssembly by remember { mutableStateOf(false) }
    val pdfPackSheetState = rememberModalBottomSheetState()

    // EPUB pack flow
    var epubPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedEpubFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    val epubPackSheetState = rememberModalBottomSheetState()

    // Image PDF pack flow
    var imagePdfPackTriggerFile by remember { mutableStateOf<PutioFile?>(null) }
    var selectedImageFiles by remember { mutableStateOf<List<PutioFile>?>(null) }
    val imagePdfPackSheetState = rememberModalBottomSheetState()

    // Folder audio picker flow (Create M4B from folder)
    var folderForAudioPicker by remember { mutableStateOf<PutioFile?>(null) }
    var selectedFolderAudioFiles by remember { mutableStateOf<List<FolderAudioFile>?>(null) }
    val folderAudioPickerState by viewModel.folderAudioPickerState.collectAsState()

    // Assembly flow
    val pendingAssemblies by viewModel.pendingAssemblies.collectAsState()
    var selectedFileForAssembly by remember { mutableStateOf<Pair<PutioFile, Boolean>?>(null) } // File, isPack
    var targetAssemblyForFile by remember { mutableStateOf<com.damarquez.putz.data.local.CalibreTransferEntity?>(null) }
    var selectedPackFilesForAssembly by remember { mutableStateOf<List<PutioFile>?>(null) }
    var selectedFolderAudioFilesForAssembly by remember { mutableStateOf<List<FolderAudioFile>?>(null) }
    var isFolderAssembly by remember { mutableStateOf(false) }

    if (selectedFileForAssembly != null && targetAssemblyForFile == null) {
        val isPack = selectedFileForAssembly!!.second
        val canShowPicker = when {
            isFolderAssembly -> selectedFolderAudioFilesForAssembly != null
            isPdfPackAssembly -> selectedPdfFilesForAssembly != null
            isPack -> selectedPackFilesForAssembly != null
            else -> true
        }
        if (canShowPicker) {
            AlertDialog(
                onDismissRequest = {
                    selectedFileForAssembly = null
                    selectedPackFilesForAssembly = null
                    selectedFolderAudioFilesForAssembly = null
                    selectedPdfFilesForAssembly = null
                    isFolderAssembly = false
                    isPdfPackAssembly = false
                },
                title = { Text("Pick Assembly") },
                text = {
                    Column {
                        pendingAssemblies.forEach { assembly ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(assembly.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(assembly.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { targetAssemblyForFile = assembly }
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        selectedFileForAssembly = null
                        selectedPackFilesForAssembly = null
                        selectedFolderAudioFilesForAssembly = null
                        selectedPdfFilesForAssembly = null
                        isFolderAssembly = false
                        isPdfPackAssembly = false
                    }) { Text("Cancel") }
                }
            )
        }
    }
    if (targetAssemblyForFile != null) {
        val (file, isPack) = selectedFileForAssembly!!
        val displayName = when {
            isFolderAssembly -> "${selectedFolderAudioFilesForAssembly?.size} audio files"
            isPdfPackAssembly -> "${selectedPdfFilesForAssembly?.size} PDF files"
            isPack -> "${selectedPackFilesForAssembly?.size} audio files"
            else -> file.displayName
        }
        CalibreConfirmationSheet(
            displayName = displayName,
            initialTitle = targetAssemblyForFile!!.title,
            initialAuthor = targetAssemblyForFile!!.author,
            onDismiss = {
                targetAssemblyForFile = null
                selectedFileForAssembly = null
                selectedPackFilesForAssembly = null
                selectedFolderAudioFilesForAssembly = null
                selectedPdfFilesForAssembly = null
                isFolderAssembly = false
                isPdfPackAssembly = false
            },
            onConfirm = { title, author, archiveMode, _, isAltVersion, _, _, _, _, _ ->
                when {
                    isFolderAssembly -> {
                        viewModel.appendFolderAudiobookPackToAssembly(targetAssemblyForFile!!.putioFileId, selectedFolderAudioFilesForAssembly!!, title, author, isAltVersion)
                    }
                    isPdfPackAssembly -> {
                        viewModel.appendPdfPackToAssembly(targetAssemblyForFile!!.putioFileId, selectedPdfFilesForAssembly!!, title, author)
                    }
                    isPack -> {
                        viewModel.appendAudiobookPackToAssembly(targetAssemblyForFile!!.putioFileId, selectedPackFilesForAssembly!!, title, author, isAltVersion)
                    }
                    else -> {
                        viewModel.appendToAssembly(targetAssemblyForFile!!.putioFileId, file, title, author, archiveMode, isAltVersion)
                    }
                }
                targetAssemblyForFile = null
                selectedFileForAssembly = null
                selectedPackFilesForAssembly = null
                selectedFolderAudioFilesForAssembly = null
                selectedPdfFilesForAssembly = null
                isFolderAssembly = false
                isPdfPackAssembly = false
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isArchive = !isPack && MetadataUtils.isArchive(file.displayName),
            forceAssemble = true,
            transferRefs = completedTransfersWithUuid,
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

    if (audiobookPackTriggerFile != null && selectedPackFiles == null && selectedPackFilesForAssembly == null) {
        // CONTRACT: stub convention — must use displayName, not name; stubs end in .sk_synced
        val audioFiles = remember(uiState) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isMultiTrackAudio(it.displayName) }
                ?: emptyList()
        }
        AudiobookPackSheet(
            audioFiles = audioFiles,
            sheetState = audiobookPackSheetState,
            onDismiss = {
                audiobookPackTriggerFile = null
                selectedFileForAssembly = null
            },
            onConfirm = { files ->
                if (selectedFileForAssembly?.second == true) {
                    selectedPackFilesForAssembly = files
                } else {
                    selectedPackFiles = files
                }
                audiobookPackTriggerFile = null
            },
        )
    }

    if (pdfPackTriggerFile != null && selectedPdfFiles == null && selectedPdfFilesForAssembly == null) {
        // CONTRACT: stub convention — must use displayName, not name; stubs end in .sk_synced
        val pdfFiles = remember(uiState) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isPdf(it.displayName) }
                ?: emptyList()
        }
        PdfPackSheet(
            pdfFiles = pdfFiles,
            sheetState = pdfPackSheetState,
            onDismiss = {
                pdfPackTriggerFile = null
                if (isPdfPackAssembly) {
                    selectedFileForAssembly = null
                    isPdfPackAssembly = false
                }
            },
            onConfirm = { files ->
                if (isPdfPackAssembly) {
                    selectedPdfFilesForAssembly = files
                } else {
                    selectedPdfFiles = files
                }
                pdfPackTriggerFile = null
            },
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
            onConfirm = { title, author, _, assembleBook, isAltVersion, _, uuid, _, tags, _ ->
                viewModel.sendAudiobookPack(packFiles, title, author, assembleBook, isAltVersion, uuid, tags)
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
            onConfirm = { title, author, _, assembleBook, _, _, uuid, _, tags, _ ->
                viewModel.sendPdfPack(pdfFiles, title, author, assembleBook, uuid, tags)
                selectedPdfFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (epubPackTriggerFile != null && selectedEpubFiles == null) {
        val epubFiles = remember(uiState) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isEpub(it.displayName) }
                ?: emptyList()
        }
        EpubPackSheet(
            epubFiles = epubFiles,
            sheetState = epubPackSheetState,
            onDismiss = { epubPackTriggerFile = null },
            onConfirm = { files ->
                selectedEpubFiles = files
                epubPackTriggerFile = null
            },
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
            onConfirm = { title, author, _, _, _, _, uuid, _, tags, _ ->
                viewModel.sendEpubPack(epubFiles, title, author, uuid, tags)
                selectedEpubFiles = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
        )
    }

    if (imagePdfPackTriggerFile != null && selectedImageFiles == null) {
        // CONTRACT: stub convention — must use displayName; filter to image siblings in current folder
        val imageFiles = remember(uiState) {
            (uiState as? FilesUiState.Success)?.files
                ?.filter { MetadataUtils.isImage(it.displayName) }
                ?: emptyList()
        }
        ImagePdfPackSheet(
            imageFiles = imageFiles,
            sheetState = imagePdfPackSheetState,
            onDismiss = { imagePdfPackTriggerFile = null },
            onConfirm = { files ->
                selectedImageFiles = files
                imagePdfPackTriggerFile = null
            },
        )
    }

    if (selectedImageFiles != null) {
        val imageFiles = selectedImageFiles!!
        val (initialTitle, initialAuthor) = remember(imageFiles) {
            MetadataUtils.extractMetadata(imageFiles.first().displayName)
        }
        CalibreConfirmationSheet(
            displayName = "${imageFiles.size} images → PDF",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = { selectedImageFiles = null },
            onConfirm = { title, author, _, _, _, _, uuid, _, tags, _ ->
                viewModel.sendImagePdfPack(imageFiles, title, author, uuid, tags)
                selectedImageFiles = null
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

    folderAudioPickerState?.let { pickerState ->
        FolderAudioPickerSheet(
            state = pickerState,
            onDismiss = {
                viewModel.dismissFolderAudioPicker()
                if (isFolderAssembly) {
                    selectedFileForAssembly = null
                    isFolderAssembly = false
                }
            },
            onConfirm = { files ->
                if (isFolderAssembly) {
                    selectedFolderAudioFilesForAssembly = files
                } else {
                    selectedFolderAudioFiles = files
                }
                viewModel.dismissFolderAudioPicker()
            },
        )
    }

    if (selectedFolderAudioFiles != null && folderForAudioPicker != null) {
        val folder = folderForAudioPicker!!
        val files = selectedFolderAudioFiles!!
        val (initialTitle, initialAuthor) = remember(folder) {
            MetadataUtils.extractMetadata(folder.name)
        }
        CalibreConfirmationSheet(
            displayName = "${files.size} audio files",
            initialTitle = initialTitle,
            initialAuthor = initialAuthor,
            onDismiss = {
                selectedFolderAudioFiles = null
                folderForAudioPicker = null
            },
            onConfirm = { title, author, _, _, _, _, uuid, _, tags, _ ->
                viewModel.sendFolderAudiobookPack(files, title, author, uuid, tags)
                selectedFolderAudioFiles = null
                folderForAudioPicker = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            transferRefs = completedTransfersWithUuid,
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
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                            )
                            accountInfo?.let { info ->
                                if (isRoot) {
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

                        // Sort Toggles
                        if (!isSearchMode) {
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
                                            } else if ((file.isLocal || file.isLan) && MetadataUtils.isArchive(file.displayName)) {
                                                onNavigateToArchive(
                                                    file.localUri,
                                                    file.lanConnectionId ?: -1L,
                                                    file.lanPath,
                                                    file.displayName,
                                                )
                                            } else if (!file.isLocal && !file.isLan && file.isSynced && MetadataUtils.isArchive(file.displayName)) {
                                                viewModel.openPutioArchive(file)
                                            }
                                        },
                                        onLongClick = { selectedFiles = selectedFiles + file },
                                        onPreview = { viewModel.previewFile(it) },
                                        onReplaceCover = { selectedFileForCover = it },
                                        onSendAsImagePdf = { imagePdfPackTriggerFile = it },
                                        onSendToCalibre = { selectedFileForCalibre = it },
                                        onSendAsAudiobookPack = { audiobookPackTriggerFile = it },
                                        onAssembleToCalibre = { target, isPack ->
                                            selectedFileForAssembly = target to isPack
                                            if (isPack) {
                                                audiobookPackTriggerFile = target
                                            }
                                        },
                                        onSendAsJoinedPdf = { pdfPackTriggerFile = it },
                                        onSendAsJoinedEpub = { epubPackTriggerFile = it },
                                        onAssembleIntoPdf = { target ->
                                            selectedFileForAssembly = target to false
                                            isPdfPackAssembly = true
                                            pdfPackTriggerFile = target
                                        },
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
                                        onCreateM4bFromFolder = { folder ->
                                            folderForAudioPicker = folder
                                            viewModel.openFolderAudioPicker(folder)
                                        },
                                        onAssembleFolderToCalibre = { folder ->
                                            folderForAudioPicker = folder
                                            isFolderAssembly = true
                                            selectedFileForAssembly = folder to true
                                            viewModel.openFolderAudioPicker(folder)
                                        },
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
                                        hasPendingAssemblies = pendingAssemblies.isNotEmpty(),
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
