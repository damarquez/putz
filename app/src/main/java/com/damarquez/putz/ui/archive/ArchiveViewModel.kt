package com.damarquez.putz.ui.archive

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.ArchiveDestination
import com.damarquez.putz.data.model.ArchiveEntry
import com.damarquez.putz.data.model.ArchiveSource
import com.damarquez.putz.data.model.ExtractionProgress
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.ArchiveRepository
import com.damarquez.putz.data.repository.AudiobookFile
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.repository.PackGroup
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.FilesRepository
import com.damarquez.putz.data.repository.LanFilesRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.files.FolderScanResult
import com.damarquez.putz.ui.files.MergeCandidateFile
import com.damarquez.putz.ui.files.MergeCandidateGroup
import com.damarquez.putz.ui.files.MergeChoiceState
import com.damarquez.putz.ui.files.MergeOutputFormat
import com.damarquez.putz.ui.files.MergeContentType
import com.damarquez.putz.ui.files.MergePickerState
import com.damarquez.putz.ui.files.MergeProcessMode
import com.damarquez.putz.ui.files.ScannedMergeFile
import com.damarquez.putz.ui.files.matches
import com.damarquez.putz.ui.files.matchesName
import com.damarquez.putz.ui.files.outputFormatOptions
import com.damarquez.putz.ui.navigation.Screen
import com.damarquez.putz.ui.viewer.ViewerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val CALIBRE_PUTIO_MAX_ENTRY_BYTES = 50L * 1024 * 1024

sealed class CalibreSendStatus {
    data class Working(val text: String) : CalibreSendStatus()
    data object Done : CalibreSendStatus()
    data class Error(val message: String) : CalibreSendStatus()
}

data class PutioPickerState(
    val currentFolderId: Long,
    val currentFolderName: String,
    val folderStack: List<Pair<Long, String>> = emptyList(),
    val dirs: List<PutioFile> = emptyList(),
    val isLoading: Boolean = true,
)

data class LanPickerState(
    val connectionId: Long,
    val connectionLabel: String,
    val currentPath: String,
    val pathStack: List<String> = emptyList(),
    val dirs: List<PutioFile> = emptyList(),
    val isLoading: Boolean = true,
)


sealed class ArchiveUiState {
    data object Loading : ArchiveUiState()
    data class Error(val message: String) : ArchiveUiState()
    data class Success(
        val allEntries: List<ArchiveEntry>,
        val currentDir: String,
        val dirStack: List<String>,
        val visibleEntries: List<ArchiveEntry>,
        val selectedEntries: Set<ArchiveEntry> = emptySet(),
        val extractionProgress: ExtractionProgress? = null,
    ) : ArchiveUiState() {
        val isSelectionMode get() = selectedEntries.isNotEmpty()
    }
}

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val archiveRepository: ArchiveRepository,
    val lanFilesRepository: LanFilesRepository,
    private val filesRepository: FilesRepository,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val archiveName: String = savedStateHandle[Screen.Archive.ARG_ARCHIVE_NAME] ?: "Archive"
    private val localUri: String? = savedStateHandle[Screen.Archive.ARG_LOCAL_URI]
    private val lanConnectionId: Long = savedStateHandle[Screen.Archive.ARG_LAN_CONNECTION_ID] ?: -1L
    private val lanPath: String? = savedStateHandle[Screen.Archive.ARG_LAN_PATH]
    private val putioFileId: Long = savedStateHandle[Screen.Archive.ARG_PUTIO_FILE_ID] ?: -1L
    // CONTRACT: stub convention — actual put.io ID of the stub (differs from putioFileId for synced files)
    // -1L is the nav defaultValue, meaning the arg was absent → fall back to putioFileId
    private val putioStubFileId: Long = (savedStateHandle[Screen.Archive.ARG_PUTIO_STUB_FILE_ID] ?: -1L)
        .takeIf { it != -1L } ?: putioFileId
    private val putioDownloadUrl: String? = savedStateHandle[Screen.Archive.ARG_PUTIO_DOWNLOAD_URL]
    private val putioFileSize: Long = savedStateHandle[Screen.Archive.ARG_PUTIO_FILE_SIZE] ?: 0L
    val putioParentFolderId: Long = savedStateHandle[Screen.Archive.ARG_PUTIO_PARENT_FOLDER_ID] ?: 0L
    private val putioIsSynced: Boolean = savedStateHandle[Screen.Archive.ARG_PUTIO_IS_SYNCED] ?: false

    val source: ArchiveSource = when {
        localUri != null -> ArchiveSource.Local(localUri)
        lanConnectionId != -1L && lanPath != null -> ArchiveSource.Lan(lanConnectionId, lanPath)
        putioFileId != -1L && putioIsSynced -> ArchiveSource.Mirror(putioFileId, null)
        putioFileId != -1L && putioDownloadUrl != null -> ArchiveSource.Putio(putioFileId, putioDownloadUrl, putioFileSize)
        else -> error("ArchiveViewModel: no valid source in saved state")
    }

    // CONTRACT: stub convention — Mirror.localPath resolved from stub JSON before first use; equals source for all other types
    private var resolvedSource: ArchiveSource = source

    val isPutio: Boolean get() = source is ArchiveSource.Putio

    private val _uiState = MutableStateFlow<ArchiveUiState>(ArchiveUiState.Loading)
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    private val _lanPickerState = MutableStateFlow<LanPickerState?>(null)
    val lanPickerState: StateFlow<LanPickerState?> = _lanPickerState.asStateFlow()

    private val _putioPickerState = MutableStateFlow<PutioPickerState?>(null)
    val putioPickerState: StateFlow<PutioPickerState?> = _putioPickerState.asStateFlow()

    private val _calibreSendStatus = MutableStateFlow<CalibreSendStatus?>(null)
    val calibreSendStatus: StateFlow<CalibreSendStatus?> = _calibreSendStatus.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val pendingAssemblies: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .map { transfers -> transfers.filter { it.status == CalibreTransferStatus.ASSEMBLED } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uploadProgress: StateFlow<Map<Long, String>> = calibreRepository.uploadProgress

    data class ViewerEvent(val kind: ViewerKind, val title: String, val filePath: String)

    private val _viewerEvent = MutableSharedFlow<ViewerEvent>()
    val viewerEvent: SharedFlow<ViewerEvent> = _viewerEvent.asSharedFlow()

    private val _previewIntent = MutableSharedFlow<Intent>()
    val previewIntent: SharedFlow<Intent> = _previewIntent.asSharedFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    // Set by selectSingleEntryForExtract; checked and cleared inside extract()
    private var singleExtractEntry: ArchiveEntry? = null

    // Merge framework — see CONTRACTS.md "Merge framework" / archive-sourced files.
    // Mirrors FilesViewModel's mergeChoiceState/mergePickerState pattern, but scans the
    // already-loaded allEntries list synchronously instead of making API calls (so Ready is
    // reached directly — Scanning is never emitted on this path).
    private val _archiveMergeChoiceState = MutableStateFlow<MergeChoiceState?>(null)
    val archiveMergeChoiceState: StateFlow<MergeChoiceState?> = _archiveMergeChoiceState.asStateFlow()

    private val _archiveMergePickerState = MutableStateFlow<MergePickerState?>(null)
    val archiveMergePickerState: StateFlow<MergePickerState?> = _archiveMergePickerState.asStateFlow()

    // The content type backing _archiveMergePickerState — read at confirm time to pick the
    // right item type/fileName, since archiveMergeChoice is cleared once the scan starts.
    // Exposed publicly (mirroring FilesViewModel.activeMergeContentType) so the UI can drive an
    // output-format picker for the active content type.
    private val _activeArchiveMergeContentType = MutableStateFlow<MergeContentType?>(null)
    val activeArchiveMergeContentType: StateFlow<MergeContentType?> = _activeArchiveMergeContentType.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = ArchiveUiState.Loading
            if (source is ArchiveSource.Mirror) {
                val localPath = calibreRepository.readStubLocalPathById(putioStubFileId)
                resolvedSource = (source as ArchiveSource.Mirror).copy(localPath = localPath)
            }
            runCatching { archiveRepository.listEntries(resolvedSource) }
                .onSuccess { entries ->
                    val restoredDir = savedStateHandle.get<String>(KEY_CURRENT_DIR) ?: ""
                    val restoredStack = savedStateHandle.get<ArrayList<String>>(KEY_DIR_STACK) ?: arrayListOf()
                    _uiState.value = ArchiveUiState.Success(
                        allEntries = entries,
                        currentDir = restoredDir,
                        dirStack = restoredStack,
                        visibleEntries = directChildren(restoredDir, entries),
                    )
                }
                .onFailure { e ->
                    _uiState.value = ArchiveUiState.Error(e.message ?: "Failed to open archive")
                }
        }
    }

    fun enterDirectory(entry: ArchiveEntry) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        val newDir = entry.path
        val newStack = s.dirStack + s.currentDir
        savedStateHandle[KEY_CURRENT_DIR] = newDir
        savedStateHandle[KEY_DIR_STACK] = ArrayList(newStack)
        _uiState.value = s.copy(
            currentDir = newDir,
            dirStack = newStack,
            visibleEntries = directChildren(newDir, s.allEntries),
            selectedEntries = emptySet(),
        )
    }

    fun navigateUp(): Boolean {
        val s = _uiState.value as? ArchiveUiState.Success ?: return false
        if (s.dirStack.isEmpty()) return false
        val parentDir = s.dirStack.last()
        val newStack = s.dirStack.dropLast(1)
        savedStateHandle[KEY_CURRENT_DIR] = parentDir
        savedStateHandle[KEY_DIR_STACK] = ArrayList(newStack)
        _uiState.value = s.copy(
            currentDir = parentDir,
            dirStack = newStack,
            visibleEntries = directChildren(parentDir, s.allEntries),
            selectedEntries = emptySet(),
        )
        return true
    }

    fun toggleSelection(entry: ArchiveEntry) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _uiState.value = s.copy(
            selectedEntries = if (entry in s.selectedEntries)
                s.selectedEntries - entry else s.selectedEntries + entry
        )
    }

    fun clearSelection() {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _uiState.value = s.copy(selectedEntries = emptySet())
    }

    fun selectSingleEntryForExtract(entry: ArchiveEntry) {
        singleExtractEntry = entry
    }

    fun extract(destination: ArchiveDestination) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        val single = singleExtractEntry
        singleExtractEntry = null
        val toExtract = when {
            single != null -> listOf(single)
            s.selectedEntries.isNotEmpty() -> s.selectedEntries.toList()
            else -> s.visibleEntries
        }
        archiveRepository.extractEntries(resolvedSource, toExtract, destination, s.currentDir)
            .onEach { progress ->
                _uiState.value = s.copy(
                    extractionProgress = progress,
                    selectedEntries = if (progress is ExtractionProgress.Done) emptySet() else s.selectedEntries,
                )
            }
            .launchIn(viewModelScope)
    }

    fun dismissExtractionResult() {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _uiState.value = s.copy(extractionProgress = null)
    }

    fun openLanPicker(connectionId: Long, connectionLabel: String, initialPath: String) {
        // Pre-populate pathStack with every ancestor so the user can navigate up from the start.
        val pathStack: List<String> = if (initialPath.isEmpty()) emptyList() else {
            val parts = initialPath.split('/')
            buildList {
                add("") // share root
                for (i in 0 until parts.size - 1) {
                    add(parts.take(i + 1).joinToString("/"))
                }
            }
        }
        val state = LanPickerState(
            connectionId = connectionId,
            connectionLabel = connectionLabel,
            currentPath = initialPath,
            pathStack = pathStack,
        )
        _lanPickerState.value = state
        loadLanPickerDirs(state)
    }

    fun lanPickerEnterDir(dir: PutioFile) {
        val state = _lanPickerState.value ?: return
        val newState = state.copy(
            currentPath = dir.lanPath ?: return,
            pathStack = state.pathStack + state.currentPath,
            dirs = emptyList(),
            isLoading = true,
        )
        _lanPickerState.value = newState
        loadLanPickerDirs(newState)
    }

    fun lanPickerNavigateUp(): Boolean {
        val state = _lanPickerState.value ?: return false
        if (state.pathStack.isEmpty()) return false
        val parentPath = state.pathStack.last()
        val newState = state.copy(
            currentPath = parentPath,
            pathStack = state.pathStack.dropLast(1),
            dirs = emptyList(),
            isLoading = true,
        )
        _lanPickerState.value = newState
        loadLanPickerDirs(newState)
        return true
    }

    fun closeLanPicker() {
        _lanPickerState.value = null
    }

    fun confirmLanExtraction() {
        val picker = _lanPickerState.value ?: return
        _lanPickerState.value = null
        extract(ArchiveDestination.Lan(picker.connectionId, picker.currentPath))
    }

    private fun loadLanPickerDirs(state: LanPickerState) {
        viewModelScope.launch {
            val dirs = runCatching {
                lanFilesRepository.listDirectory(state.connectionId, state.currentPath)
                    .last()
                    .filter { it.isFolder }
            }.getOrDefault(emptyList())
            val current = _lanPickerState.value ?: return@launch
            if (current.connectionId == state.connectionId && current.currentPath == state.currentPath) {
                _lanPickerState.value = current.copy(dirs = dirs, isLoading = false)
            }
        }
    }

    fun openPutioPicker() {
        val state = PutioPickerState(currentFolderId = putioParentFolderId, currentFolderName = "")
        _putioPickerState.value = state
        loadPutioPickerDirs(state)
    }

    fun putioPickerEnterDir(dir: PutioFile) {
        val state = _putioPickerState.value ?: return
        val newState = state.copy(
            currentFolderId = dir.id,
            currentFolderName = dir.name,
            folderStack = state.folderStack + (state.currentFolderId to state.currentFolderName),
            dirs = emptyList(),
            isLoading = true,
        )
        _putioPickerState.value = newState
        loadPutioPickerDirs(newState)
    }

    fun putioPickerNavigateUp(): Boolean {
        val state = _putioPickerState.value ?: return false
        if (state.folderStack.isEmpty()) return false
        val (parentId, parentName) = state.folderStack.last()
        val newState = state.copy(
            currentFolderId = parentId,
            currentFolderName = parentName,
            folderStack = state.folderStack.dropLast(1),
            dirs = emptyList(),
            isLoading = true,
        )
        _putioPickerState.value = newState
        loadPutioPickerDirs(newState)
        return true
    }

    fun closePutioPicker() {
        _putioPickerState.value = null
    }

    fun confirmPutioExtraction() {
        val picker = _putioPickerState.value ?: return
        _putioPickerState.value = null
        extract(ArchiveDestination.Putio(picker.currentFolderId))
    }

    private fun loadPutioPickerDirs(state: PutioPickerState) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = runCatching { filesRepository.listFiles(token, state.currentFolderId) }.getOrNull()
            val dirs: List<PutioFile>
            val folderName: String
            if (result is NetworkResult.Success) {
                dirs = result.data.first.filter { it.isFolder }.sortedBy { it.name.lowercase() }
                folderName = result.data.second?.name ?: state.currentFolderName
            } else {
                dirs = emptyList()
                folderName = state.currentFolderName
            }
            val current = _putioPickerState.value ?: return@launch
            if (current.currentFolderId == state.currentFolderId) {
                _putioPickerState.value = current.copy(dirs = dirs, isLoading = false, currentFolderName = folderName)
            }
        }
    }

    fun defaultLanPath(connectionId: Long): String =
        if (source is ArchiveSource.Lan && source.connectionId == connectionId)
            source.path.substringBeforeLast('/', "")
        else ""

    fun dismissSnackbar() { _snackbarMessage.value = null }
    fun dismissCalibreSendStatus() { _calibreSendStatus.value = null }

    fun previewEntry(entry: ArchiveEntry) {
        viewModelScope.launch {
            _isPreviewLoading.value = true
            try {
                val tempFile = withContext(Dispatchers.IO) {
                    val previewsDir = java.io.File(context.cacheDir, "previews").apply { mkdirs() }
                    archiveRepository.extractEntryToTempFile(resolvedSource, entry, previewsDir)
                }
                val viewerKind = withContext(Dispatchers.IO) { ViewerKind.forFile(tempFile) }
                    ?: ViewerKind.forFileName(entry.name)
                if (viewerKind != null) {
                    _viewerEvent.emit(ViewerEvent(viewerKind, entry.name, tempFile.absolutePath))
                } else {
                    val extension = MimeTypeMap.getFileExtensionFromUrl(entry.name)
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
                    val fileUri = FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", tempFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    _previewIntent.emit(intent)
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "Preview error: ${e.message}"
            } finally {
                _isPreviewLoading.value = false
            }
        }
    }

    suspend fun checkBookExists(title: String, author: String): Long? {
        val dbFile = java.io.File(context.filesDir, "metadata.db")
        return calibreRepository.checkExists(dbFile, title, author)
    }

    suspend fun checkBookExistsByUuid(uuid: String): CalibreBookMatch? {
        val dbFile = java.io.File(context.filesDir, "metadata.db")
        return calibreRepository.checkExistsByUuid(dbFile, uuid)
    }

    fun sendEntryToCalibre(
        entry: ArchiveEntry,
        title: String,
        author: String,
        assembleBook: Boolean = false,
        assemblyFileId: Long? = null,
        calibreBookUuid: String? = null,
        isAltVersion: Boolean = false,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        overrideUuid: String? = null,
        overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ) {
        val useDaemonLocal = source is ArchiveSource.Lan ||
            (source is ArchiveSource.Putio && putioIsSynced)

        if (!useDaemonLocal && source is ArchiveSource.Putio && entry.size > CALIBRE_PUTIO_MAX_ENTRY_BYTES) {
            _snackbarMessage.value = "File exceeds 50 MB — extract it first, then send to Calibre"
            return
        }

        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            if (useDaemonLocal) {
                sendEntryToCalibreViaDaemon(entry, title, author, assembleBook, assemblyFileId, calibreBookUuid, googleAccount, overrideTitle, overrideAuthor, overrideUuid, overrideTags, overrideProtected)
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            val tempId = System.currentTimeMillis()
            var tempFile: java.io.File? = null

            com.damarquez.putz.sync.TransferPrepareService.start(context)
            try {
                _calibreSendStatus.value = CalibreSendStatus.Working("Extracting…")
                calibreRepository.updateUploadProgress(tempId, "Extracting…")
                calibreRepository.addTransfer(
                    putioFileId = tempId,
                    fileName = entry.name,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    downloadUrl = null,
                    isTempUpload = true,
                    assembleBook = assembleBook && assemblyFileId == null,
                    calibreBookUuid = calibreBookUuid,
                    isUploading = true,
                )

                tempFile = archiveRepository.extractEntryToTempFile(resolvedSource, entry, context.cacheDir)

                _calibreSendStatus.value = CalibreSendStatus.Working("Uploading…")

                val rootFiles = filesRepository.listFiles(putioToken, 0).dataOrNull()?.first ?: emptyList()
                var tempFolderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id
                if (tempFolderId == null) {
                    val r = filesRepository.createFolder(putioToken, 0, ".putz_attachments")
                    tempFolderId = (r as? NetworkResult.Success)?.data?.id
                }
                if (tempFolderId == null) throw java.io.IOException("Could not find or create .putz_attachments")

                val retryableCodes = setOf(429, 500, 502, 503, 504)
                val maxAttempts = 5
                var delayMs = 5_000L
                var uploadResult: NetworkResult<com.damarquez.putz.data.model.PutioFile>? = null
                for (attempt in 1..maxAttempts) {
                    uploadResult = filesRepository.uploadFileFromStream(
                        putioToken, tempFolderId, entry.name,
                        tempFile.inputStream(), tempFile.length(),
                    ) { bytesWritten, totalBytes ->
                        val pct = if (totalBytes > 0) (bytesWritten * 100 / totalBytes).toInt() else 0
                        calibreRepository.updateUploadProgress(tempId, "1/1 · $pct%")
                        _calibreSendStatus.value = CalibreSendStatus.Working("Uploading… $pct%")
                    }
                    val errorCode = (uploadResult as? NetworkResult.Error)?.code
                    when {
                        uploadResult is NetworkResult.Success -> break
                        errorCode == 403 || errorCode == 507 -> {
                            val msg = "put.io storage full — free up space and try again"
                            throw java.io.IOException(msg)
                        }
                        attempt < maxAttempts && (errorCode in retryableCodes || errorCode == null) -> {
                            kotlinx.coroutines.delay(delayMs)
                            delayMs = minOf(delayMs * 2, 60_000L)
                        }
                        else -> break
                    }
                }

                if (uploadResult !is NetworkResult.Success)
                    throw java.io.IOException("Upload failed: ${(uploadResult as NetworkResult.Error).message}")

                val uploadedId = uploadResult.data.id
                val downloadUrl = filesRepository.getDownloadUrl(putioToken, uploadedId)
                calibreRepository.updateUploadProgress(tempId, null)

                if (assemblyFileId != null) {
                    val targetFileName = if (isAltVersion) {
                        val ext = entry.name.substringAfterLast('.', "")
                        if (ext.isNotEmpty()) entry.name.substringBeforeLast('.') + "." + ext + "_bkp"
                        else entry.name
                    } else entry.name
                    // A single pack-shaped file (e.g. one CBR/image) must go through the same
                    // content-type-aware merge path as picking 2+ files does (see
                    // appendArchiveMerge), instead of always appending a hardcoded "SINGLE" item —
                    // otherwise it can never fold into an existing CBZ pack in the target assembly
                    // and the daemon defaults it to a standalone PDF conversion.
                    val contentType = MergeContentType.entries.firstOrNull { it.matchesName(entry.name) }
                    val added = if (contentType != null) {
                        val cbzUpgradeType = when (contentType) {
                            MergeContentType.IMAGES -> "IMAGE_CBZ_PACK"
                            MergeContentType.CBR -> "CBR_CBZ_PACK"
                            else -> null
                        }
                        val effectiveType = if (cbzUpgradeType != null) {
                            val assembly = calibreRepository.getTransfer(assemblyFileId)
                            if (assembly != null && calibreRepository.compatibleAssemblyItem(assembly, cbzUpgradeType) != null)
                                cbzUpgradeType
                            else contentType.itemType
                        } else contentType.itemType
                        val newItem = CalibreBatchItem(
                            type = effectiveType,
                            putio_file_id = uploadedId,
                            fileName = contentType.outputFileName,
                            files = listOf(AudiobookFile(
                                putio_file_id = uploadedId,
                                fileName = targetFileName,
                                download_url = downloadUrl,
                            )),
                        )
                        calibreRepository.mergeIntoAssemblyItem(
                            assemblyFileId = assemblyFileId,
                            newItem = newItem,
                            newFileIds = listOf(uploadedId),
                        )
                    } else {
                        val newItem = CalibreBatchItem(
                            type = "SINGLE",
                            putio_file_id = uploadedId,
                            fileName = targetFileName,
                            download_url = downloadUrl,
                        )
                        calibreRepository.appendToAssembly(
                            assemblyFileId = assemblyFileId,
                            newItem = newItem,
                            newFileIds = listOf(uploadedId),
                        )
                    }
                    calibreRepository.removeTransfer(tempId)
                    _snackbarMessage.value = if (added) "Added to assembly"
                        else "\"$targetFileName\" is already in this assembly"
                } else if (assembleBook) {
                    calibreRepository.removeTransfer(tempId)
                    calibreRepository.addTransfer(
                        putioFileId = uploadedId,
                        fileName = entry.name,
                        title = title,
                        author = author,
                        googleAccount = googleAccount,
                        downloadUrl = downloadUrl,
                        isTempUpload = true,
                        assembleBook = true,
                        calibreBookUuid = calibreBookUuid,
                    )
                    _snackbarMessage.value = "Book assembled"
                } else {
                    calibreRepository.updateTransferAfterUpload(tempId, uploadedId, downloadUrl, googleAccount)
                    _snackbarMessage.value = "Transfer requested for $title"
                }

                _calibreSendStatus.value = CalibreSendStatus.Done

            } catch (e: Exception) {
                calibreRepository.updateUploadProgress(tempId, null)
                calibreRepository.removeTransfer(tempId)
                val msg = e.message ?: "Unknown error"
                _snackbarMessage.value = "Failed: $msg"
                _calibreSendStatus.value = CalibreSendStatus.Error(msg)
            } finally {
                com.damarquez.putz.sync.TransferPrepareService.stop(context)
                tempFile?.delete()
            }
        }
    }

    private suspend fun sendEntryToCalibreViaDaemon(
        entry: ArchiveEntry,
        title: String,
        author: String,
        assembleBook: Boolean,
        assemblyFileId: Long?,
        calibreBookUuid: String?,
        googleAccount: String,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        overrideUuid: String? = null,
        overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ) {
        try {
            _calibreSendStatus.value = CalibreSendStatus.Working("Sending to Calibre…")

            // CONTRACT: stub convention — for synced put.io archives, putioFileId is the original file ID
            // and putioStubFileId is the actual put.io ID of the stub (read local_path from stub content)
            data class ResolvedSource(val fileId: Long, val smbPath: String?, val useLocal: Boolean, val localPath: String?)
            val resolved = when (source) {
                is ArchiveSource.Lan -> {
                    val conn = lanFilesRepository.getConnectionById(source.connectionId)
                        ?: throw java.io.IOException("LAN connection not found")
                    val uncPath = buildUncPath(conn.host, conn.shareName, source.path)
                    ResolvedSource(System.currentTimeMillis(), uncPath, false, null)
                }
                is ArchiveSource.Putio -> {
                    val localPath = if (putioIsSynced) calibreRepository.readStubLocalPathById(putioStubFileId) else null
                    ResolvedSource(putioFileId, null, true, localPath)
                }
                else -> throw IllegalStateException("Unexpected source for daemon path")
            }
            val (fileId, smbPath, useLocal, localPath) = resolved

            if (assemblyFileId != null) {
                // See the matching comment in sendEntryToCalibre: a single pack-shaped file must
                // go through the same content-type-aware merge path as 2+ files (appendArchiveMerge),
                // instead of always appending a hardcoded "ARCHIVE_ENTRY" item, so it can fold into
                // an existing CBZ pack in the target assembly rather than defaulting to a PDF.
                val contentType = MergeContentType.entries.firstOrNull { it.matchesName(entry.name) }
                val added = if (contentType != null) {
                    val cbzUpgradeType = when (contentType) {
                        MergeContentType.IMAGES -> "IMAGE_CBZ_PACK"
                        MergeContentType.CBR -> "CBR_CBZ_PACK"
                        else -> null
                    }
                    val effectiveType = if (cbzUpgradeType != null) {
                        val assembly = calibreRepository.getTransfer(assemblyFileId)
                        if (assembly != null && calibreRepository.compatibleAssemblyItem(assembly, cbzUpgradeType) != null)
                            cbzUpgradeType
                        else contentType.itemType
                    } else contentType.itemType
                    val newItem = CalibreBatchItem(
                        type = effectiveType,
                        putio_file_id = fileId,
                        fileName = contentType.outputFileName,
                        files = listOf(AudiobookFile(
                            putio_file_id = fileId,
                            fileName = entry.name,
                            use_local = if (useLocal) true else null,
                            local_path = localPath,
                            smb_path = smbPath,
                            archive_entry = entry.path,
                            archive_file_name = archiveName,
                        )),
                    )
                    calibreRepository.mergeIntoAssemblyItem(
                        assemblyFileId = assemblyFileId,
                        newItem = newItem,
                        newFileIds = listOf(fileId),
                        overrideTitle = overrideTitle,
                        overrideAuthor = overrideAuthor,
                        overrideUuid = overrideUuid,
                        overrideTags = overrideTags,
                        overrideProtected = overrideProtected,
                    )
                } else {
                    val newItem = CalibreBatchItem(
                        type = "ARCHIVE_ENTRY",
                        putio_file_id = fileId,
                        fileName = entry.name,
                        use_local = if (useLocal) true else null,
                        local_path = localPath,
                        smb_path = smbPath,
                        archive_entry = entry.path,
                    )
                    calibreRepository.appendToAssembly(
                        assemblyFileId = assemblyFileId,
                        newItem = newItem,
                        newFileIds = listOf(fileId),
                        overrideTitle = overrideTitle,
                        overrideAuthor = overrideAuthor,
                        overrideUuid = overrideUuid,
                        overrideTags = overrideTags,
                        overrideProtected = overrideProtected,
                    )
                }
                _snackbarMessage.value = if (added) "Added to assembly"
                    else "\"${entry.name}\" is already in this assembly"
            } else {
                calibreRepository.addTransfer(
                    putioFileId = fileId,
                    fileName = entry.name,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    downloadUrl = null,
                    useLocal = useLocal,
                    smbPath = smbPath,
                    assembleBook = assembleBook,
                    calibreBookUuid = calibreBookUuid,
                    archiveEntry = entry.path,
                    localPath = localPath,
                )
                _snackbarMessage.value = "Transfer requested for $title"
            }

            _calibreSendStatus.value = CalibreSendStatus.Done
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            _snackbarMessage.value = "Failed: $msg"
            _calibreSendStatus.value = CalibreSendStatus.Error(msg)
        }
    }

    // ---- Merge framework: trigger merges from inside this archive, treating it like a folder ----
    // See CONTRACTS.md "Merge framework" / archive-sourced files. Reuses the merge framework's
    // Compose dialogs (MergeContentTypeChoiceDialog/MergeProcessChoiceDialog/MergePackSheet) and
    // MergeCandidateFile/MergeCandidateGroup/MergePickerState types unchanged — none of
    // FilesViewModel.kt/resolveForMerge/sendMergeFiles is touched, so the already-verified
    // put.io-folder merge flow is unaffected.

    // Wraps an archive entry as a PutioFile purely so MergeCandidateFile/MergePackSheet can
    // display it (file.displayName) — never resolved as a real local/remote/synced file. lanPath
    // (otherwise meaningless here; isLan stays false so nothing treats this as a real LAN file)
    // carries this entry's full in-archive path so sendArchiveMerge can recover it directly
    // instead of re-deriving prefixes from MergeCandidateGroup's labels.
    private fun ArchiveEntry.asMergeCandidatePutioFile(): PutioFile =
        PutioFile(id = path.hashCode().toLong(), name = name, lanPath = path, size = size)

    // Exposed for the merge selection sheet's live size-total display (FileSizeProgress.kt).
    // Archive-sourced candidates are never isSynced, so this is only ever called for parity —
    // it never actually fires a fetch on this screen.
    suspend fun readStubFileSize(file: PutioFile): Long? = calibreRepository.readStubFileSize(file)

    private fun scanArchiveFlat(rootDir: String, allEntries: List<ArchiveEntry>, matches: (ArchiveEntry) -> Boolean): List<MergeCandidateFile> {
        val prefix = if (rootDir.isEmpty()) "" else "$rootDir/"
        return allEntries
            .filter { !it.isDirectory && it.path.startsWith(prefix) && matches(it) }
            .sortedBy { it.path }
            .map { MergeCandidateFile(it.asMergeCandidatePutioFile(), it.path.removePrefix(prefix)) }
    }

    /** File-trigger entry point: pick siblings of the same content type in the current directory. */
    fun openArchiveFileMerge(entry: ArchiveEntry) {
        val contentType = MergeContentType.entries.firstOrNull { it.matchesName(entry.name) } ?: return
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _activeArchiveMergeContentType.value = contentType
        val siblings = scanArchiveFlat(s.currentDir, s.allEntries) { contentType.matchesName(it.name) }
        _archiveMergePickerState.value = MergePickerState.ReadyFlat(
            s.currentDir.ifEmpty { archiveName }, siblings,
        )
    }

    /** Directory-trigger entry point: ask content type, then flatten vs. subfolders-as-chapters.
     * allEntries is always fully in memory already, so the full-tree tally is synchronous —
     * this goes straight to Ready, no Scanning state. */
    fun openArchiveMergeChoice(dir: ArchiveEntry) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        val allFiles = scanArchiveFlat(dir.path, s.allEntries) { true }
        val scanned = allFiles.map {
            ScannedMergeFile(it.file, it.relativePath, MergeContentType.entries.firstOrNull { ct -> ct.matches(it.file) })
        }
        val prefix = if (dir.path.isEmpty()) "" else "${dir.path}/"
        val subfolderCount = s.allEntries.count { it.isDirectory && it.path.startsWith(prefix) && it.path != dir.path }
        _archiveMergeChoiceState.value = MergeChoiceState.Ready(dir.name, FolderScanResult(scanned, subfolderCount))
    }

    fun chooseArchiveMergeContentType(type: MergeContentType) {
        val ready = _archiveMergeChoiceState.value as? MergeChoiceState.Ready ?: return
        _archiveMergeChoiceState.value = ready.copy(contentType = type)
        // No subfolders contain this type — "flatten vs. chapters" is moot, skip straight to it.
        if (!ready.scan.hasSubfoldersFor(type)) {
            startArchiveMergeFolderScan(MergeProcessMode.FLATTEN)
        }
    }

    fun dismissArchiveMergeChoice() {
        _archiveMergeChoiceState.value = null
    }

    fun startArchiveMergeFolderScan(mode: MergeProcessMode) {
        val ready = _archiveMergeChoiceState.value as? MergeChoiceState.Ready ?: return
        val contentType = ready.contentType ?: return
        _archiveMergeChoiceState.value = null
        _activeArchiveMergeContentType.value = contentType
        when (mode) {
            MergeProcessMode.FLATTEN -> {
                val files = ready.scan.flatCandidates(contentType)
                _archiveMergePickerState.value = if (files.isEmpty())
                    MergePickerState.Error(ready.folderName, "No ${contentType.label.lowercase()} found in this folder")
                else MergePickerState.ReadyFlat(ready.folderName, files)
            }
            MergeProcessMode.SUBFOLDERS_AS_CHAPTERS -> {
                val groups = ready.scan.groupedCandidates(contentType, ready.folderName)
                _archiveMergePickerState.value = if (groups.isEmpty())
                    MergePickerState.Error(ready.folderName, "No ${contentType.label.lowercase()} found in this folder")
                else MergePickerState.ReadyGrouped(ready.folderName, groups)
            }
        }
    }

    fun dismissArchiveMergePicker() {
        _archiveMergePickerState.value = null
    }

    private data class ResolvedArchiveForMerge(
        val fileId: Long, val smbPath: String?, val useLocal: Boolean,
        val localPath: String?, val downloadUrl: String?,
    )

    // Resolves the CONTAINING archive's own source fields once for the whole send (not per
    // entry) — same three-way model every other merge file uses. Unlike sendEntryToCalibreViaDaemon
    // (ARCHIVE_ENTRY), a remote/unsynced Putio archive is NOT routed to the client-upload fallback
    // here: the daemon's _resolve_item_source already supports download_url generically, so the
    // merge framework's archive_entry resolution can download the archive once server-side instead.
    private suspend fun resolveArchiveForMerge(): ResolvedArchiveForMerge? = when (val src = source) {
        is ArchiveSource.Lan -> {
            val conn = lanFilesRepository.getConnectionById(src.connectionId)
            if (conn == null) {
                _snackbarMessage.value = "LAN connection not found"
                null
            } else {
                ResolvedArchiveForMerge(System.currentTimeMillis(), buildUncPath(conn.host, conn.shareName, src.path), false, null, null)
            }
        }
        is ArchiveSource.Mirror -> {
            val localPath = calibreRepository.readStubLocalPathById(putioStubFileId)
            ResolvedArchiveForMerge(putioFileId, null, true, localPath, null)
        }
        is ArchiveSource.Putio -> {
            if (putioIsSynced) {
                val localPath = calibreRepository.readStubLocalPathById(putioStubFileId)
                ResolvedArchiveForMerge(putioFileId, null, true, localPath, null)
            } else {
                ResolvedArchiveForMerge(src.fileId, null, false, null, src.downloadUrl)
            }
        }
        is ArchiveSource.Local -> null // handled separately via sendArchiveMergeViaUpload
    }

    /** Confirm step for both the file-trigger and directory-trigger archive merge flows. */
    fun sendArchiveMerge(
        files: List<MergeCandidateFile>?,
        groups: List<MergeCandidateGroup>?,
        title: String,
        author: String,
        calibreBookUuid: String? = null,
        tags: String? = null,
        isProtected: Boolean = false,
        assembleBook: Boolean = false,
        outputFormat: MergeOutputFormat,
        ignoreCover: Boolean = false,
    ) {
        val contentType = _activeArchiveMergeContentType.value ?: return
        val effectiveType = outputFormat.itemType
        val effectiveFileName = outputFormat.outputFileName
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            if (source is ArchiveSource.Local) {
                sendArchiveMergeViaUpload(contentType, files, groups, title, author, calibreBookUuid, tags, isProtected, assembleBook, googleAccount, outputFormat, ignoreCover)
                return@launch
            }

            val resolved = resolveArchiveForMerge() ?: return@launch
            val dummyArchiveFile = PutioFile(id = resolved.fileId, name = archiveName)

            fun toAudiobookFile(candidate: MergeCandidateFile) = AudiobookFile(
                putio_file_id = resolved.fileId,
                fileName = candidate.file.name,
                download_url = resolved.downloadUrl,
                smb_path = resolved.smbPath,
                use_local = if (resolved.useLocal) true else null,
                local_path = resolved.localPath,
                archive_entry = candidate.file.lanPath!!,
                archive_file_name = archiveName,
            )

            calibreRepository.addMergeTransfer(
                type = effectiveType,
                fileName = effectiveFileName,
                files = files?.map { dummyArchiveFile to toAudiobookFile(it) },
                groups = groups?.map { g -> g.label to g.files.map { dummyArchiveFile to toAudiobookFile(it) } },
                title = title,
                author = author,
                googleAccount = googleAccount,
                assembleBook = assembleBook,
                calibreBookUuid = calibreBookUuid,
                tags = tags,
                isProtected = isProtected,
                ignoreCover = ignoreCover,
            )
            _snackbarMessage.value = if (assembleBook) "Merge queued for assembly" else "Merge transfer requested"
        }
    }

    /** Append the currently-pending archive merge into an existing assembly instead of creating
     *  a new request. Mirrors [sendArchiveMerge] but calls [CalibreRepository.mergeIntoAssemblyItem]
     *  instead of [CalibreRepository.addMergeTransfer]. Local-archive sources are not supported
     *  (they require uploading to put.io first; use [sendArchiveMerge] with assembleBook=true instead). */
    fun appendArchiveMerge(
        assemblyFileId: Long,
        files: List<MergeCandidateFile>?,
        groups: List<MergeCandidateGroup>?,
        outputFormat: MergeOutputFormat,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        overrideUuid: String? = null,
        overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ) {
        val contentType = _activeArchiveMergeContentType.value ?: return
        viewModelScope.launch {
            calibreRepository.markAssemblyAppendPending(assemblyFileId)
            try {
                if (source is ArchiveSource.Local) {
                    _snackbarMessage.value = "Assembly append not supported for local archives"
                    return@launch
                }
                val resolved = resolveArchiveForMerge() ?: return@launch

                fun toAudiobookFile(candidate: MergeCandidateFile) = AudiobookFile(
                    putio_file_id = resolved.fileId,
                    fileName = candidate.file.name,
                    download_url = resolved.downloadUrl,
                    smb_path = resolved.smbPath,
                    use_local = if (resolved.useLocal) true else null,
                    local_path = resolved.localPath,
                    archive_entry = candidate.file.lanPath!!,
                    archive_file_name = archiveName,
                )

                // When appending to an existing assembly that already contains a compatible slot
                // for one of this content type's output formats, fold into that slot instead of
                // creating a stray duplicate item (see "Assembly duplicate-format bug"), even if
                // it differs from the format the user picked for this new batch. Otherwise honor
                // the caller-supplied outputFormat.
                val assembly = calibreRepository.getTransfer(assemblyFileId)
                val compatibleOption = assembly?.let { a ->
                    contentType.outputFormatOptions().firstOrNull { calibreRepository.compatibleAssemblyItem(a, it.itemType) != null }
                }
                val (effectiveType, effectiveFileName) = if (compatibleOption != null) {
                    compatibleOption.itemType to compatibleOption.outputFileName
                } else {
                    outputFormat.itemType to outputFormat.outputFileName
                }

                val (newItem, newIds) = when {
                    groups != null -> {
                        val resolvedGroups = groups.map { g -> PackGroup(g.label, g.files.map { toAudiobookFile(it) }) }
                        CalibreBatchItem(
                            type = effectiveType, putio_file_id = resolved.fileId,
                            fileName = effectiveFileName, groups = resolvedGroups,
                        ) to groups.flatMap { g -> g.files.map { resolved.fileId } }
                    }
                    files != null -> {
                        val resolvedFiles = files.map { toAudiobookFile(it) }
                        CalibreBatchItem(
                            type = effectiveType, putio_file_id = resolved.fileId,
                            fileName = effectiveFileName, files = resolvedFiles,
                        ) to files.map { resolved.fileId }
                    }
                    else -> return@launch
                }

                val added = calibreRepository.mergeIntoAssemblyItem(
                    assemblyFileId, newItem, newIds,
                    overrideTitle = overrideTitle, overrideAuthor = overrideAuthor,
                    overrideUuid = overrideUuid, overrideTags = overrideTags,
                    overrideProtected = overrideProtected,
                )
                _snackbarMessage.value = if (added) "Added to assembly" else "Format already in this assembly"
            } finally {
                calibreRepository.clearAssemblyAppendPending(assemblyFileId)
            }
        }
    }

    // Fallback for ArchiveSource.Local (a raw on-device archive — the daemon has no access to it
    // at all): extract each chosen entry client-side via extractEntryToTempFile (already used by
    // sendEntryToCalibre for the single-entry case), then upload it to put.io like a normal
    // on-device file. Once uploaded, no archive_entry/archive_file_name is needed — it's a plain file.
    private suspend fun sendArchiveMergeViaUpload(
        contentType: MergeContentType,
        files: List<MergeCandidateFile>?,
        groups: List<MergeCandidateGroup>?,
        title: String,
        author: String,
        calibreBookUuid: String?,
        tags: String?,
        isProtected: Boolean,
        assembleBook: Boolean,
        googleAccount: String,
        outputFormat: MergeOutputFormat,
        ignoreCover: Boolean = false,
    ) {
        val effectiveType = outputFormat.itemType
        val effectiveFileName = outputFormat.outputFileName
        val flatCandidates = files ?: groups?.flatMap { it.files } ?: emptyList()
        if (flatCandidates.isEmpty()) {
            _snackbarMessage.value = "No files to merge"
            return
        }
        val s = _uiState.value as? ArchiveUiState.Success
        val allEntries = s?.allEntries ?: emptyList()
        val entryByPath = allEntries.associateBy { it.path }

        val putioToken = settingsRepository.authTokenFlow.first()
        val rootFiles = filesRepository.listFiles(putioToken, 0).dataOrNull()?.first ?: emptyList()
        var tempFolderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id
        if (tempFolderId == null) {
            val r = filesRepository.createFolder(putioToken, 0, ".putz_attachments")
            tempFolderId = (r as? NetworkResult.Success)?.data?.id
        }
        if (tempFolderId == null) {
            _snackbarMessage.value = "Could not find or create .putz_attachments"
            return
        }

        com.damarquez.putz.sync.TransferPrepareService.start(context)
        try {
            val uploaded = mutableMapOf<String, AudiobookFile>() // keyed by entry path
            for ((index, candidate) in flatCandidates.withIndex()) {
                val entryPath = candidate.file.lanPath!!
                val entry = entryByPath[entryPath] ?: continue
                calibreRepository.updatePrepareProgress((index + 1) to flatCandidates.size)
                val tempFile = try {
                    archiveRepository.extractEntryToTempFile(source, entry, context.cacheDir)
                } catch (e: Exception) {
                    _snackbarMessage.value = "Failed to extract ${entry.name}: ${e.message}"
                    return
                }
                try {
                    val uploadResult = filesRepository.uploadFileFromStream(
                        putioToken, tempFolderId, entry.name, tempFile.inputStream(), tempFile.length(),
                    ) { _, _ -> }
                    if (uploadResult !is NetworkResult.Success) {
                        _snackbarMessage.value = "Upload failed for ${entry.name}"
                        return
                    }
                    val downloadUrl = filesRepository.getDownloadUrl(putioToken, uploadResult.data.id)
                    uploaded[entryPath] = AudiobookFile(uploadResult.data.id, entry.name, download_url = downloadUrl)
                } finally {
                    tempFile.delete()
                }
            }

            val dummyArchiveFile = PutioFile(id = System.currentTimeMillis(), name = archiveName)
            calibreRepository.addMergeTransfer(
                type = effectiveType,
                fileName = effectiveFileName,
                files = files?.mapNotNull { c -> uploaded[c.file.lanPath]?.let { dummyArchiveFile to it } },
                groups = groups?.map { g -> g.label to g.files.mapNotNull { c -> uploaded[c.file.lanPath]?.let { dummyArchiveFile to it } } },
                title = title,
                author = author,
                googleAccount = googleAccount,
                assembleBook = assembleBook,
                calibreBookUuid = calibreBookUuid,
                tags = tags,
                isProtected = isProtected,
                ignoreCover = ignoreCover,
            )
            _snackbarMessage.value = if (assembleBook) "Merge queued for assembly" else "Merge transfer requested"
        } finally {
            calibreRepository.updatePrepareProgress(null)
            com.damarquez.putz.sync.TransferPrepareService.stop(context)
        }
    }

    private fun buildUncPath(host: String, shareName: String, path: String): String {
        val normalized = path.replace('/', '\\').trimStart('\\')
        return "\\\\$host\\$shareName\\$normalized"
    }

    companion object {
        private const val KEY_CURRENT_DIR = "archive_current_dir"
        private const val KEY_DIR_STACK = "archive_dir_stack"
    }

    private fun directChildren(dir: String, all: List<ArchiveEntry>): List<ArchiveEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val seen = mutableSetOf<String>()
        val result = mutableListOf<ArchiveEntry>()

        for (entry in all) {
            val cleanPath = entry.path.trimEnd('/')
            if (!cleanPath.startsWith(prefix)) continue
            val relative = cleanPath.removePrefix(prefix)
            if (relative.isEmpty()) continue

            val firstComponent = relative.substringBefore('/')
            if (firstComponent in seen) continue
            seen.add(firstComponent)

            if (relative.contains('/')) {
                // This entry is deeper — add a synthetic directory for firstComponent
                val childPath = (if (prefix.isEmpty()) "" else prefix) + firstComponent
                result.add(ArchiveEntry(childPath, firstComponent, true, 0L, 0L))
            } else {
                result.add(entry)
            }
        }

        return result.sortedWith(
            compareByDescending<ArchiveEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }
}
