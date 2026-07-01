package com.damarquez.putz.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.AccountInfo
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.model.PutioFile.Companion.TRASH_ROOT_ID
import com.damarquez.putz.data.repository.AudiobookFile
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.FilesRepository
import com.damarquez.putz.data.repository.PackGroup
import com.damarquez.putz.data.transport.LanDaemonTransport
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.Screen
import com.damarquez.putz.ui.viewer.ViewerKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.damarquez.putz.util.MetadataUtils
import java.io.File
import javax.inject.Inject

sealed class FilesUiState {
    data object Loading : FilesUiState()
    data class Success(
        val files: List<PutioFile>,
        val parent: PutioFile?,
        val isRefreshing: Boolean = false,
        val searchResults: List<PutioFile>? = null,
        val isSearching: Boolean = false,
        val isScanning: Boolean = false,
        val isPreviewLoading: Boolean = false,
    ) : FilesUiState()
    data class Error(val message: String) : FilesUiState()
}

enum class FilesTab {
    CLOUD, SPECIAL
}

enum class SortOrder {
    NONE, ASCENDING, DESCENDING
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val filesRepository: FilesRepository,
    private val localFilesRepository: com.damarquez.putz.data.repository.LocalFilesRepository,
    private val lanFilesRepository: com.damarquez.putz.data.repository.LanFilesRepository,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
    private val lanDaemonTransport: LanDaemonTransport,
) : ViewModel() {

    val parentId: Long = savedStateHandle[Screen.Files.ARG_PARENT_ID] ?: 0L
    val folderName: String = savedStateHandle[Screen.Files.ARG_FOLDER_NAME] ?: "Your Files"
    val highlightFileId: Long = savedStateHandle[Screen.Files.ARG_HIGHLIGHT_ID] ?: -1L
    val localUri: String? = savedStateHandle[Screen.Files.ARG_LOCAL_URI]
    val lanConnectionId: Long = savedStateHandle[Screen.Files.ARG_LAN_CONNECTION_ID] ?: -1L
    val lanPath: String? = savedStateHandle[Screen.Files.ARG_LAN_PATH]
    val argTab: String? = savedStateHandle[Screen.Files.ARG_TAB]

    // True when viewing .putz_hidden or any folder nested inside it (propagated via tab="hidden")
    val isInHiddenScope: Boolean = argTab == "hidden" || folderName == ".putz_hidden"

    private val _currentTab = MutableStateFlow(
        if (argTab != null) {
            try { FilesTab.valueOf(argTab) } catch (e: Exception) { FilesTab.CLOUD }
        } else if (parentId == 0L) {
            FilesTab.CLOUD
        } else if (localUri != null || lanConnectionId != -1L || folderName == ".putz_attachments") {
            FilesTab.SPECIAL
        } else {
            FilesTab.CLOUD
        }
    )
    val currentTab: StateFlow<FilesTab> = _currentTab.asStateFlow()

    private val _nameSort = MutableStateFlow(SortOrder.NONE)
    val nameSort: StateFlow<SortOrder> = _nameSort.asStateFlow()

    private val _dateSort = MutableStateFlow(SortOrder.NONE)
    val dateSort: StateFlow<SortOrder> = _dateSort.asStateFlow()

    fun toggleNameSort() {
        _dateSort.value = SortOrder.NONE
        _nameSort.value = when (_nameSort.value) {
            SortOrder.NONE -> SortOrder.ASCENDING
            SortOrder.ASCENDING -> SortOrder.DESCENDING
            SortOrder.DESCENDING -> SortOrder.NONE
        }
        refreshList()
    }

    fun toggleDateSort() {
        _nameSort.value = SortOrder.NONE
        _dateSort.value = when (_dateSort.value) {
            SortOrder.NONE -> SortOrder.DESCENDING // Default to recent
            SortOrder.DESCENDING -> SortOrder.ASCENDING
            SortOrder.ASCENDING -> SortOrder.NONE
        }
        refreshList()
    }

    private fun refreshList() {
        val current = _uiState.value
        if (current is FilesUiState.Success) {
            _uiState.value = current.copy(files = augmentWithLocal(rawApiFiles))
        }
    }

    private var rawApiFiles: List<PutioFile> = emptyList()

    fun setTab(tab: FilesTab) {
        if (_currentTab.value == tab && parentId == 0L) return
        _currentTab.value = tab
        if (parentId == 0L) {
            loadFiles(isRefresh = true)
        }
    }

    val putioLocalLanConnectionId: StateFlow<Long?> = settingsRepository.putioLocalLanConnectionIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val putioLocalLanPath: StateFlow<String> = settingsRepository.putioLocalLanPathFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _plexPickerState = MutableStateFlow<LanFolderPickerState?>(null)
    val plexPickerState: StateFlow<LanFolderPickerState?> = _plexPickerState.asStateFlow()

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val _allSyncedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    val allSyncedFolderIds: StateFlow<Set<Long>> = _allSyncedFolderIds.asStateFlow()

    private val _previewIntent = MutableSharedFlow<Intent>()
    val previewIntent: SharedFlow<Intent> = _previewIntent.asSharedFlow()

    data class ViewerEvent(val kind: ViewerKind, val title: String, val filePath: String)
    private val _viewerEvent = MutableSharedFlow<ViewerEvent>()
    val viewerEvent: SharedFlow<ViewerEvent> = _viewerEvent.asSharedFlow()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    val googleAccount: StateFlow<String> = settingsRepository.googleTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val pendingAssemblies: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .map { transfers ->
            transfers.filter { it.status == CalibreTransferStatus.ASSEMBLED && it.transferType == "CALIBRE" }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingPlexAssemblies: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .map { transfers ->
            transfers.filter { it.status == CalibreTransferStatus.ASSEMBLED && it.transferType == "PLEX" }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTransfersWithUuid: StateFlow<List<TransferRef>> = calibreRepository.getTransfers()
        .map { transfers ->
            transfers
                .filter { it.status == CalibreTransferStatus.COMPLETED && it.calibreBookUuid != null }
                .map { TransferRef(it.calibreBookUuid!!, it.title, it.author) }
                .distinctBy { it.uuid }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Counts in-flight "send to Calibre" pack operations (resolving/uploading files before
    // the transfer row exists), so the UI can show an animation during that otherwise-silent gap.
    private val _pendingTransferPreparations = MutableStateFlow(0)
    val isPreparingTransfer: StateFlow<Boolean> = _pendingTransferPreparations
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // (filesDone, totalFiles) for the pack currently being gathered, when known. Backed by
    // CalibreRepository (not local state) so TransferPrepareService can observe it too.
    val transferPreparationProgress: StateFlow<Pair<Int, Int>?> = calibreRepository.prepareProgress

    private inline fun trackTransferPreparation(crossinline block: () -> Job): Job {
        // Foreground the process for the duration of this operation — otherwise Android can
        // kill it outright once the screen locks, before the transfer row is ever persisted,
        // and the whole "send to Calibre" request silently disappears.
        //
        // The start/stop decision must be derived from the SAME atomic update as the count
        // change, not from a separate .value read afterwards — two overlapping preparations
        // racing that read-then-branch could otherwise miss the transition back to zero and
        // leave the service (and its notification) running forever with nothing left to stop it.
        var wasIdle = false
        _pendingTransferPreparations.update { current ->
            wasIdle = current == 0
            current + 1
        }
        if (wasIdle) {
            com.damarquez.putz.sync.TransferPrepareService.start(context)
        }
        val job = block()
        job.invokeOnCompletion {
            calibreRepository.updatePrepareProgress(null)
            var isNowIdle = false
            _pendingTransferPreparations.update { current ->
                val next = current - 1
                isNowIdle = next == 0
                next
            }
            if (isNowIdle) {
                com.damarquez.putz.sync.TransferPrepareService.stop(context)
            }
        }
        return job
    }

    private val _openParentFolderEvent = MutableSharedFlow<Triple<Long, String, Long>>()
    val openParentFolderEvent: SharedFlow<Triple<Long, String, Long>> = _openParentFolderEvent.asSharedFlow()

    data class PutioArchiveEvent(
        val fileId: Long,           // CONTRACT: stub convention — original file ID (syncedFileId) for requests
        val stubFileId: Long,       // CONTRACT: stub convention — actual put.io ID of the stub (for deletion and content reading)
        val fileName: String,
        val downloadUrl: String,
        val fileSize: Long,
        val parentFolderId: Long,
        val isSynced: Boolean,
    )
    private val _putioArchiveEvent = MutableSharedFlow<PutioArchiveEvent>()
    val putioArchiveEvent: SharedFlow<PutioArchiveEvent> = _putioArchiveEvent.asSharedFlow()

    private val _isSearchMode = MutableStateFlow(false)
    val isSearchMode: StateFlow<Boolean> = _isSearchMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null

    init {
        clearPreviewsCache()
        loadFiles()
        if (parentId == 0L) loadAccountInfo()
    }

    private fun clearPreviewsCache() {
        viewModelScope.launch {
            try {
                val previewsDir = File(context.cacheDir, "previews")
                if (previewsDir.exists()) {
                    previewsDir.listFiles()?.forEach { it.delete() }
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    fun previewFile(file: PutioFile) {
        // Audio streams progressively via ExoPlayer instead of going through the generic preview
        // path below, which would otherwise wait for the *entire* file to download/copy first —
        // fine for a small image, unworkable for a multi-GB audiobook. No isPreviewLoading wait
        // either: the player screen shows its own buffering state. isLocal/isLan files are already
        // fast local/already-copied access, so they keep using the path below.
        if (MetadataUtils.isAudio(file.displayName) && !file.isLocal && !file.isLan) {
            viewModelScope.launch {
                if (file.isSynced) {
                    if (!settingsRepository.lanEnabledFlow.first() || !lanDaemonTransport.isReachable()) {
                        _snackbarMessage.value = "Preview not available — LAN connection with active daemon required"
                        return@launch
                    }
                    // The daemon's /mirror/file endpoint serves with Werkzeug range-request
                    // support (conditional=True), so ExoPlayer can seek without re-fetching
                    // from the start — same as streaming straight from put.io below.
                    val host = settingsRepository.lanHostFlow.first().trim()
                    val port = settingsRepository.lanPortFlow.first()
                    val localPath = calibreRepository.readStubLocalPath(file)
                    val url = buildString {
                        append("http://$host:$port/api/mirror/file/${file.syncedFileId}")
                        if (localPath != null) append("?local_path=${Uri.encode(localPath)}")
                    }
                    _viewerEvent.emit(ViewerEvent(ViewerKind.AUDIO, file.displayName, url))
                } else {
                    val token = settingsRepository.authTokenFlow.first()
                    val url = filesRepository.getDownloadUrl(token, file.id)
                    _viewerEvent.emit(ViewerEvent(ViewerKind.AUDIO, file.displayName, url))
                }
            }
            return
        }

        viewModelScope.launch {
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(isPreviewLoading = true)
            }

            try {
                // localFile is the real on-disk copy (cheap to reuse for LAN/synced/remote — we
                // just wrote it — and only requires an extra copy for the isLocal/SAF-uri case).
                val (uri: Uri, localFile: File) = when {
                    file.isLocal && file.localUri != null -> {
                        val parsedUri = Uri.parse(file.localUri)
                        val resolved = withContext(Dispatchers.IO) { resolveFileForViewer(parsedUri, file.displayName) }
                        parsedUri to resolved
                    }
                    file.isLan && file.lanConnectionId != null && file.lanPath != null -> {
                        val targetFile = File(File(context.cacheDir, "previews"), file.name)
                        if (!targetFile.exists()) {
                            withContext(Dispatchers.IO) {
                                targetFile.parentFile?.mkdirs()
                                lanFilesRepository.openFileStream(file.lanConnectionId, file.lanPath).use { input ->
                                    targetFile.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                        }
                        FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", targetFile) to targetFile
                    }
                    // CONTRACT: stub convention — preview requires LAN; downloading the stub itself is useless
                    file.isSynced && (!settingsRepository.lanEnabledFlow.first() || !lanDaemonTransport.isReachable()) -> {
                        _snackbarMessage.value = "Preview not available — LAN connection with active daemon required"
                        return@launch
                    }
                    file.isSynced -> {
                        val targetFile = File(File(context.cacheDir, "previews"), file.displayName)
                        if (!targetFile.exists()) {
                            withContext(Dispatchers.IO) { targetFile.parentFile?.mkdirs() }
                            val localPath = calibreRepository.readStubLocalPath(file)
                            val err = lanDaemonTransport.downloadMirrorFile(file.syncedFileId, targetFile, localPath)
                            if (err != null) {
                                _snackbarMessage.value = "Preview failed: $err"
                                return@launch
                            }
                        }
                        FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", targetFile) to targetFile
                    }
                    else -> {
                        val token = settingsRepository.authTokenFlow.first()
                        val url = filesRepository.getDownloadUrl(token, file.id)
                        val targetFile = File(File(context.cacheDir, "previews"), file.name)
                        if (!targetFile.exists()) {
                            val result = filesRepository.downloadToFile(url, targetFile)
                            if (result is NetworkResult.Error) {
                                _snackbarMessage.value = "Preview failed: ${result.message}"
                                return@launch
                            }
                        }
                        FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", targetFile) to targetFile
                    }
                }

                // Real content wins over the (possibly wrong) extension — old ebook/scene
                // releases frequently mislabel plain text/RTF as .doc, or worse. Audio has no
                // content-sniffing signature, so it's checked by extension first.
                val viewerKind = if (MetadataUtils.isAudio(file.displayName)) {
                    ViewerKind.AUDIO
                } else {
                    withContext(Dispatchers.IO) { ViewerKind.forFile(localFile) }
                        ?: ViewerKind.forFileName(file.displayName)
                }
                if (viewerKind != null) {
                    _viewerEvent.emit(ViewerEvent(viewerKind, file.displayName, localFile.absolutePath))
                } else {
                    val extension = MimeTypeMap.getFileExtensionFromUrl(file.displayName)
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    _previewIntent.emit(intent)
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "Preview error: ${e.message}"
            } finally {
                val finalState = _uiState.value
                if (finalState is FilesUiState.Success) {
                    _uiState.value = finalState.copy(isPreviewLoading = false)
                }
            }
        }
    }

    /** In-app viewers need a real File (e.g. for ZipFile access), not a content:// grant meant for external apps. */
    private fun resolveFileForViewer(uri: Uri, displayName: String): File {
        if (uri.scheme == "file") return File(requireNotNull(uri.path))
        val dest = File(File(context.cacheDir, "previews"), "viewer_$displayName")
        if (!dest.exists()) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    fun loadFiles(isRefresh: Boolean = false) {
        if (_isSearchMode.value) {
            search(_searchQuery.value)
            return
        }
        viewModelScope.launch {
            val isLocalRoot = parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID
            val isLocalFolder = localUri != null || parentId <= com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_FOLDER_PREFIX_ID - 1000
            val isLanRoot = parentId == com.damarquez.putz.data.repository.LanFilesRepository.LAN_ROOT_ID
            val isLanBrowsing = lanConnectionId != -1L

            if (isLocalRoot) {
                _uiState.value = if (isRefresh) (uiState.value as? FilesUiState.Success)?.copy(isRefreshing = true) ?: FilesUiState.Loading else FilesUiState.Loading
                val attachments = localFilesRepository.getAttachments()
                _uiState.value = FilesUiState.Success(files = attachments, parent = null)
                return@launch
            }

            if (isLocalFolder && localUri != null) {
                _uiState.value = if (isRefresh) (uiState.value as? FilesUiState.Success)?.copy(isRefreshing = true) ?: FilesUiState.Loading else FilesUiState.Loading
                localFilesRepository.listLocalFolder(localUri).collect { files ->
                    _uiState.value = FilesUiState.Success(
                        files = files,
                        parent = null,
                        isRefreshing = false,
                        isScanning = true
                    )
                }
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    _uiState.value = current.copy(isScanning = false)
                }
                return@launch
            }

            if (isLanRoot) {
                _uiState.value = if (isRefresh) (uiState.value as? FilesUiState.Success)?.copy(isRefreshing = true) ?: FilesUiState.Loading else FilesUiState.Loading
                val connections = lanFilesRepository.getConnectionsSync()
                val connectionFiles = connections.map { conn ->
                    PutioFile(
                        id = com.damarquez.putz.data.repository.LanFilesRepository.connectionVirtualId(conn.id),
                        name = conn.label,
                        fileType = "FOLDER",
                        isLan = true,
                        lanPath = "",
                        lanConnectionId = conn.id,
                    )
                }
                _uiState.value = FilesUiState.Success(files = connectionFiles, parent = null)
                return@launch
            }

            if (isLanBrowsing) {
                _uiState.value = if (isRefresh) (uiState.value as? FilesUiState.Success)?.copy(isRefreshing = true) ?: FilesUiState.Loading else FilesUiState.Loading
                lanFilesRepository.listDirectory(lanConnectionId, lanPath ?: "").collect { files ->
                    _uiState.value = FilesUiState.Success(
                        files = files,
                        parent = null,
                        isRefreshing = false,
                        isScanning = true,
                    )
                }
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    _uiState.value = current.copy(isScanning = false)
                }
                return@launch
            }

            // Normal put.io loading
            if (!isRefresh) {
                val cached = filesRepository.getCached(parentId)
                if (cached != null) {
                    val (files, parent) = cached
                    rawApiFiles = files
                    _uiState.value = FilesUiState.Success(
                        files = augmentWithLocal(files),
                        parent = parent,
                    )
                    enrichSyncedFileSizes(files)
                    return@launch
                }
                _uiState.value = FilesUiState.Loading
            } else {
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    _uiState.value = current.copy(isRefreshing = true)
                }
            }

            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.listFiles(token, parentId)) {
                is NetworkResult.Success -> {
                    val (files, parent) = result.data
                    rawApiFiles = files
                    _uiState.value = FilesUiState.Success(files = augmentWithLocal(files), parent = parent)
                    _allSyncedFolderIds.value = emptySet()
                    checkSubfolderSyncState(files.filter { it.isFolder && !it.isLocal && !it.isLan }, token)
                    enrichSyncedFileSizes(files)
                }
                is NetworkResult.Error -> {
                    _uiState.value = FilesUiState.Error(result.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun checkSubfolderSyncState(subfolders: List<PutioFile>, token: String) {
        if (subfolders.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            subfolders.forEach { folder ->
                launch {
                    val result = filesRepository.listFiles(token, folder.id)
                    if (result is NetworkResult.Success) {
                        val children = result.data.first
                        val fileChildren = children.filter { !it.isFolder }
                        if (fileChildren.isNotEmpty() && fileChildren.all { it.isSynced }) {
                            _allSyncedFolderIds.update { it + folder.id }
                        }
                    }
                }
            }
        }
    }

    // CONTRACT: stub convention — put.io reports the tiny stub's own size, not the original
    // file's. Fetches the real size from each stub's JSON content (small batches, cached in
    // CalibreRepository) and patches it into the displayed list as results arrive.
    private fun enrichSyncedFileSizes(files: List<PutioFile>) {
        val stubs = files.filter { it.isSynced }
        if (stubs.isEmpty()) return
        viewModelScope.launch {
            stubs.chunked(5).forEach { chunk ->
                chunk.map { stub ->
                    async {
                        val size = calibreRepository.readStubFileSize(stub)
                        if (size != null && size > 0) applySyncedFileSize(stub.id, size)
                    }
                }.awaitAll()
            }
        }
    }

    private fun applySyncedFileSize(fileId: Long, size: Long) {
        val current = _uiState.value as? FilesUiState.Success ?: return
        _uiState.value = current.copy(
            files = current.files.map { if (it.id == fileId) it.copy(size = size) else it }
        )
    }

    private fun augmentWithLocal(apiFiles: List<PutioFile>): List<PutioFile> {
        val list = if (parentId == 0L) {
            val localRoot = PutioFile(
                id = com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID,
                name = "Local Files",
                fileType = "FOLDER",
                isLocal = true,
            )
            val lanRoot = PutioFile(
                id = com.damarquez.putz.data.repository.LanFilesRepository.LAN_ROOT_ID,
                name = "LAN Files",
                fileType = "FOLDER",
                isLan = true,
            )
            val trashRoot = PutioFile(
                id = TRASH_ROOT_ID,
                name = "Trash",
                fileType = "FOLDER",
                isTrash = true,
            )
            val putioLocalConnId = putioLocalLanConnectionId.value
            val putioLocalPath = putioLocalLanPath.value
            val putioLocalRoot = if (putioLocalConnId != null) {
                PutioFile(
                    id = PutioFile.PUTIO_LOCAL_ROOT_ID,
                    name = "put.io Local",
                    fileType = "FOLDER",
                    isLan = true,
                    lanConnectionId = putioLocalConnId,
                    lanPath = putioLocalPath,
                )
            } else null

            when (_currentTab.value) {
                FilesTab.CLOUD -> {
                    listOfNotNull(putioLocalRoot, trashRoot) + apiFiles.filter { it.name != ".putz_attachments" }
                }
                FilesTab.SPECIAL -> {
                    listOfNotNull(localRoot, lanRoot) + apiFiles.filter { it.name == ".putz_attachments" }
                }
            }
        } else apiFiles

        val nameSortOrder = _nameSort.value
        val dateSortOrder = _dateSort.value

        return when {
            nameSortOrder != SortOrder.NONE -> {
                if (nameSortOrder == SortOrder.ASCENDING) {
                    list.sortedBy { it.name.lowercase() }
                } else {
                    list.sortedByDescending { it.name.lowercase() }
                }
            }
            dateSortOrder != SortOrder.NONE -> {
                if (dateSortOrder == SortOrder.ASCENDING) {
                    list.sortedBy { it.createdAt ?: "" }
                } else {
                    list.sortedByDescending { it.createdAt ?: "" }
                }
            }
            else -> {
                // Default sort: Folders first, then name
                list.sortedWith(
                    compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() }
                )
            }
        }
    }

    fun downloadFile(file: PutioFile) {
        viewModelScope.launch {
            val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager

            // CONTRACT: stub convention — serve the real file from the LAN mirror, not the put.io stub
            if (file.isSynced) {
                val host = settingsRepository.lanHostFlow.first().trim()
                val port = settingsRepository.lanPortFlow.first()
                val apiKey = settingsRepository.lanApiKeyFlow.first()
                val localPath = calibreRepository.readStubLocalPath(file)
                val encodedPath = localPath?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                val base = "http://$host:$port/api/mirror/file/${file.syncedFileId}"
                val url = if (encodedPath != null) "$base?local_path=$encodedPath" else base
                val safeFileName = file.displayName.replace(Regex("[\\[\\]<>|*?\"']"), "_")
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    .setTitle(file.displayName)
                    .setDescription("Downloading from LAN mirror")
                    .addRequestHeader("X-Sidekick-Key", apiKey)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, safeFileName)
                downloadManager.enqueue(request)
                _snackbarMessage.value = "Download started: ${file.displayName}"
                return@launch
            }

            val token = settingsRepository.authTokenFlow.first()
            val putioUrl = filesRepository.getDownloadUrl(token, file.id)
            val directUrl = filesRepository.resolveDirectDownloadUrl(putioUrl)
            val safeFileName = file.name.replace(Regex("[\\[\\]<>|*?\"']"), "_")
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(directUrl))
                .setTitle(file.name)
                .setDescription("Downloading from put.io")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, safeFileName)
            downloadManager.enqueue(request)
            _snackbarMessage.value = "Download started: ${file.name}"
        }
    }

    // CONTRACT: PRIORITY_PUTIO_SYNC
    fun requestPrioritySync(file: PutioFile) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }
            val success = calibreRepository.sendPrioritySyncRequest(file, googleAccount)
            _snackbarMessage.value = if (success)
                "Priority sync requested for ${file.displayName}"
            else
                "Failed to send priority sync request"
        }
    }

    fun copyDownloadLink(file: PutioFile) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val url = filesRepository.getDownloadUrl(token, file.id)
            
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Download Link", url)
            clipboardManager.setPrimaryClip(clip)
            _snackbarMessage.value = "Link copied to clipboard"
        }
    }

    fun attachLocal(uri: android.net.Uri, name: String, isFolder: Boolean) {
        viewModelScope.launch {
            localFilesRepository.attach(uri, name, isFolder)
            if (parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID) {
                loadFiles(isRefresh = true)
            }
        }
    }

    private suspend fun uploadLocalFileIfNecessary(
        file: PutioFile,
        putioToken: String,
        progressKey: Long = file.id,
        fileIndex: Int = 1,
        totalFiles: Int = 1,
        clearProgressOnSuccess: Boolean = true,
    ): Long? {
        if (file.isLan) {
            // LAN files must be handled via SMB path before reaching this function.
            android.util.Log.e("FilesViewModel", "uploadLocalFileIfNecessary called with LAN file ${file.name} — this is a bug")
            return null
        }
        if (!file.isLocal) {
            android.util.Log.d("FilesViewModel", "File ${file.name} is remote, skipping upload.")
            return file.id
        }
        if (file.localUri == null) return null

        val localSize: Long = withContext(Dispatchers.IO) {
            val uri = android.net.Uri.parse(file.localUri!!)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }

        // 1. Find or create .putz_attachments
        val rootFiles = filesRepository.listFiles(putioToken, 0).dataOrNull()?.first ?: emptyList()
        var tempFolderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id

        if (tempFolderId == null) {
            val createResult = filesRepository.createFolder(putioToken, 0, ".putz_attachments")
            if (createResult is NetworkResult.Success) {
                tempFolderId = createResult.data.id
            } else {
                _snackbarMessage.value = "Failed to create temp folder: ${(createResult as NetworkResult.Error).message}"
                return null
            }
        }

        // 2. Check if a file with the same name and size already exists — skip upload if so
        if (localSize > 0) {
            val folderContents = filesRepository.listFiles(putioToken, tempFolderId!!).dataOrNull()?.first ?: emptyList()
            val existing = folderContents.find { it.name == file.name && it.size == localSize }
            if (existing != null) {
                android.util.Log.d("FilesViewModel", "Skipping upload for ${file.name}: already on put.io (ID ${existing.id}, size $localSize)")
                _snackbarMessage.value = "\"${file.name}\" already uploaded, reusing…"
                return existing.id
            }
        }

        android.util.Log.d("FilesViewModel", "uploadLocalFileIfNecessary for ${file.name}")
        _snackbarMessage.value = "Uploading \"${file.name}\" to put.io..."

        // 3. Upload with exponential-backoff retry for rate limits, server errors, and hangs
        val retryableCodes = setOf(429, 500, 502, 503, 504)
        val maxAttempts = 5
        // Allow enough time for large files: ~200 KB/s minimum throughput + 5 min for server
        // processing after all bytes are sent. Floor 20 min, cap 60 min.
        val uploadTimeoutMs = if (localSize > 0) {
            val transferMs = (localSize / 200L) // ms at 200 KB/s
            (transferMs + 5 * 60_000L).coerceIn(20 * 60_000L, 60 * 60_000L)
        } else {
            20 * 60_000L
        }
        var delayMs = 5_000L
        var lastProgressMs = 0L
        for (attempt in 1..maxAttempts) {
            val uploadResult = try {
                withTimeout(uploadTimeoutMs) {
                    val progressCallback: (Long, Long) -> Unit = { bytesWritten, totalBytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs >= 500L || bytesWritten == totalBytes) {
                            lastProgressMs = now
                            val pct = if (totalBytes > 0) (bytesWritten * 100 / totalBytes).toInt() else 0
                            calibreRepository.updateUploadProgress(progressKey, "$fileIndex/$totalFiles · $pct%")
                        }
                    }
                    val uri = android.net.Uri.parse(file.localUri!!)
                    filesRepository.uploadFile(
                        putioToken, tempFolderId!!, file.name, uri, context.contentResolver, progressCallback
                    )
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.w("FilesViewModel", "Upload attempt $attempt timed out for ${file.name}")
                NetworkResult.Error("Upload timed out", null)
            } catch (e: Exception) {
                android.util.Log.w("FilesViewModel", "Upload attempt $attempt threw for ${file.name}: ${e.message}")
                NetworkResult.Error(e.message ?: "Unknown error", null)
            }
            val errorCode = (uploadResult as? NetworkResult.Error)?.code
            when {
                uploadResult is NetworkResult.Success -> {
                    if (clearProgressOnSuccess) calibreRepository.updateUploadProgress(progressKey, null)
                    return uploadResult.data.id
                }
                attempt < maxAttempts && (errorCode in retryableCodes || errorCode == null) -> {
                    val reason = if (errorCode == null) "timed out" else "throttled ($errorCode)"
                    android.util.Log.w("FilesViewModel", "Upload $reason, attempt $attempt/$maxAttempts for ${file.name}, retrying in ${delayMs}ms")
                    _snackbarMessage.value = "Upload $reason, retrying \"${file.name}\" ($attempt/${maxAttempts - 1})…"
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, 60_000L)
                }
                else -> {
                    calibreRepository.updateUploadProgress(progressKey, null)
                    val msg = "Upload failed: ${(uploadResult as NetworkResult.Error).message}"
                    _snackbarMessage.value = msg
                    calibreRepository.setTransferErrorMessage(progressKey, msg)
                    return null
                }
            }
        }
        calibreRepository.updateUploadProgress(progressKey, null)
        val msg = "Upload failed after $maxAttempts attempts: ${file.name}"
        _snackbarMessage.value = msg
        calibreRepository.setTransferErrorMessage(progressKey, msg)
        return null
    }

    fun sendToCalibre(file: PutioFile, title: String, author: String, archiveMode: String? = null, assembleBook: Boolean = false, isAltVersion: Boolean = false, calibreBookUuid: String? = null, isProtected: Boolean = false, tags: String? = null) {
        trackTransferPreparation { viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            
            if (file.isSynced) {
                val syncedFileName = if (isAltVersion) {
                    val ext = file.displayName.substringAfterLast('.', "")
                    if (ext.isNotEmpty()) file.displayName.substringBeforeLast('.') + "." + ext + "_bkp" else file.displayName
                } else file.displayName
                // Save to DB immediately so the transfer is never silently lost if the
                // coroutine is cancelled (e.g. navigating away). local_path is resolved below.
                calibreRepository.addTransfer(
                    putioFileId = file.syncedFileId,
                    fileName = syncedFileName,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    downloadUrl = null,
                    archiveMode = archiveMode,
                    isTempUpload = false,
                    assembleBook = assembleBook,
                    calibreBookUuid = calibreBookUuid,
                    useLocal = true,
                    localPath = null,
                    isProtected = isProtected,
                    tags = tags,
                )
                _snackbarMessage.value = if (assembleBook) "Book assembled" else "Transfer queued for $title"
                // Resolve local path then dispatch (or just update batchData for assembled).
                // Safe to cancel here — the DB record already exists and the polling loop
                // will retry any PENDING transfer that was never dispatched.
                val localPath = calibreRepository.readStubLocalPath(file)
                calibreRepository.resolveLocalPathAndDispatch(file.syncedFileId, localPath, googleAccount)
                if (!assembleBook) _snackbarMessage.value = "Transfer requested for $title"
                return@launch
            } else if (file.isLan) {
                val conn = file.lanConnectionId?.let { lanFilesRepository.getConnectionById(it) }
                if (conn == null || file.lanPath == null) {
                    _snackbarMessage.value = "LAN connection info missing"
                    return@launch
                }
                val targetFileName = if (isAltVersion) {
                    val ext = file.name.substringAfterLast('.', "")
                    if (ext.isNotEmpty()) file.name.substringBeforeLast('.') + "." + ext + "_bkp" else file.name
                } else file.name
                calibreRepository.addTransfer(
                    putioFileId = file.id,
                    fileName = targetFileName,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    downloadUrl = null,
                    archiveMode = archiveMode,
                    isTempUpload = false,
                    assembleBook = assembleBook,
                    calibreBookUuid = calibreBookUuid,
                    smbPath = buildUncPath(conn.host, conn.shareName, file.lanPath),
                    isProtected = isProtected,
                    tags = tags,
                )
                _snackbarMessage.value = if (assembleBook) "Book assembled" else "Transfer requested for $title"
            } else if (file.isLocal) {
                calibreRepository.addTransfer(
                    putioFileId = file.id,
                    fileName = file.name,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    downloadUrl = null,
                    archiveMode = archiveMode,
                    isTempUpload = true,
                    sourceLocalUri = file.localUri,
                    assembleBook = assembleBook,
                    calibreBookUuid = calibreBookUuid,
                    isUploading = true,
                    localUrisJson = file.localUri?.let { """["$it"]""" },
                    isProtected = isProtected,
                    tags = tags,
                )

                val uploadedId = uploadLocalFileIfNecessary(file, putioToken, progressKey = file.id)
                if (uploadedId != null) {
                    val downloadUrl = filesRepository.getDownloadUrl(putioToken, uploadedId)
                    var targetFileName = file.name
                    var finalId = uploadedId

                    if (isAltVersion) {
                        val ext = targetFileName.substringAfterLast('.', "")
                        if (ext.isNotEmpty()) {
                            val newName = targetFileName.substringBeforeLast('.') + "." + ext + "_bkp"
                            val renameResult = filesRepository.renameFile(putioToken, uploadedId, newName)
                            if (renameResult is NetworkResult.Success) {
                                targetFileName = newName
                            }
                        }
                    }

                    if (assembleBook) {
                        // Just update status to ASSEMBLED
                        val transfer = calibreRepository.getTransfer(file.id)
                        if (transfer != null) {
                            val initialItem = CalibreBatchItem(
                                type = if (archiveMode != null) "ARCHIVE" else "SINGLE",
                                putio_file_id = finalId,
                                fileName = targetFileName,
                                download_url = downloadUrl,
                                archiveMode = archiveMode
                            )
                            calibreRepository.removeTransfer(file.id)
                            calibreRepository.addTransfer(
                                putioFileId = finalId,
                                fileName = targetFileName,
                                title = title,
                                author = author,
                                googleAccount = googleAccount,
                                downloadUrl = downloadUrl,
                                archiveMode = archiveMode,
                                isTempUpload = true,
                                sourceLocalUri = file.localUri,
                                assembleBook = true,
                                calibreBookUuid = calibreBookUuid,
                                isProtected = isProtected,
                                tags = tags,
                            )
                        }
                    } else {
                        calibreRepository.updateTransferAfterUpload(file.id, finalId, downloadUrl, googleAccount)
                    }
                    _snackbarMessage.value = if (assembleBook) "Book assembled" else "Transfer requested for $title"
                } else {
                    calibreRepository.removeTransfer(file.id)
                    _snackbarMessage.value = "Upload failed"
                }
            } else {
                // Standard remote file path
                var targetFileId = file.id
                var targetFileName = file.name

                if (isAltVersion) {
                    val ext = targetFileName.substringAfterLast('.', "")
                    if (ext.isNotEmpty()) {
                        val newName = targetFileName.substringBeforeLast('.') + "." + ext + "_bkp"
                        val renameResult = filesRepository.renameFile(putioToken, targetFileId, newName)
                        if (renameResult is NetworkResult.Success) {
                            targetFileName = newName
                        } else {
                            _snackbarMessage.value = "Failed to rename: ${(renameResult as NetworkResult.Error).message}"
                            return@launch
                        }
                    }
                }

                val downloadUrl = filesRepository.getDownloadUrl(putioToken, targetFileId)
                calibreRepository.addTransfer(
                    putioFileId = targetFileId,
                    fileName = targetFileName,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    downloadUrl = downloadUrl,
                    archiveMode = archiveMode,
                    isTempUpload = false,
                    sourceLocalUri = null,
                    assembleBook = assembleBook,
                    calibreBookUuid = calibreBookUuid,
                    isProtected = isProtected,
                    tags = tags,
                )
                _snackbarMessage.value = if (assembleBook) "Book assembled" else "Transfer requested for $title"
            }
        } }
    }

    fun appendToAssembly(
        assemblyFileId: Long,
        file: PutioFile,
        archiveMode: String? = null,
        isAltVersion: Boolean = false,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        overrideUuid: String? = null,
        overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ) {
        viewModelScope.launch {
            calibreRepository.markAssemblyAppendPending(assemblyFileId)
            try {
            val putioToken = settingsRepository.authTokenFlow.first()

            if (file.isSynced) {
                val targetFileName = if (isAltVersion) {
                    val ext = file.displayName.substringAfterLast('.', "")
                    if (ext.isNotEmpty()) file.displayName.substringBeforeLast('.') + "." + ext + "_bkp" else file.displayName
                } else file.displayName
                val localPath = calibreRepository.readStubLocalPath(file)
                val newItem = CalibreBatchItem(
                    type = if (archiveMode != null) "ARCHIVE" else "SINGLE",
                    putio_file_id = file.syncedFileId,
                    fileName = targetFileName,
                    archiveMode = archiveMode,
                    use_local = true,
                    local_path = localPath,
                )
                val added = calibreRepository.appendToAssembly(
                    assemblyFileId = assemblyFileId,
                    newItem = newItem,
                    newFileIds = listOf(file.syncedFileId),
                    overrideTitle = overrideTitle,
                    overrideAuthor = overrideAuthor,
                    overrideUuid = overrideUuid,
                    overrideTags = overrideTags,
                    overrideProtected = overrideProtected,
                )
                _snackbarMessage.value = if (added) "File added to assembly"
                    else "\"$targetFileName\" is already in this assembly"
                return@launch
            }

            if (file.isLan) {
                val conn = file.lanConnectionId?.let { lanFilesRepository.getConnectionById(it) }
                if (conn == null || file.lanPath == null) {
                    _snackbarMessage.value = "LAN connection info missing"
                    return@launch
                }
                val targetFileName = if (isAltVersion) {
                    val ext = file.name.substringAfterLast('.', "")
                    if (ext.isNotEmpty()) file.name.substringBeforeLast('.') + "." + ext + "_bkp" else file.name
                } else file.name
                val newItem = CalibreBatchItem(
                    type = if (archiveMode != null) "ARCHIVE" else "SINGLE",
                    putio_file_id = file.id,
                    fileName = targetFileName,
                    smb_path = buildUncPath(conn.host, conn.shareName, file.lanPath),
                    archiveMode = archiveMode,
                )
                val added = calibreRepository.appendToAssembly(
                    assemblyFileId = assemblyFileId,
                    newItem = newItem,
                    newFileIds = listOf(file.id),
                    overrideTitle = overrideTitle,
                    overrideAuthor = overrideAuthor,
                    overrideUuid = overrideUuid,
                    overrideTags = overrideTags,
                    overrideProtected = overrideProtected,
                )
                _snackbarMessage.value = if (added) "File added to assembly"
                    else "\"$targetFileName\" is already in this assembly"
                return@launch
            }

            var targetFileId = uploadLocalFileIfNecessary(file, putioToken, progressKey = assemblyFileId) ?: return@launch
            var targetFileName = file.name

            if (isAltVersion) {
                val ext = targetFileName.substringAfterLast('.', "")
                if (ext.isNotEmpty()) {
                    val newName = targetFileName.substringBeforeLast('.') + "." + ext + "_bkp"
                    val renameResult = filesRepository.renameFile(putioToken, targetFileId, newName)
                    if (renameResult is NetworkResult.Success) {
                        targetFileName = newName
                    } else {
                        _snackbarMessage.value = "Failed to rename: ${(renameResult as NetworkResult.Error).message}"
                        return@launch
                    }
                }
            }

            val downloadUrl = filesRepository.getDownloadUrl(putioToken, targetFileId)

            val newItem = CalibreBatchItem(
                type = if (archiveMode != null) "ARCHIVE" else "SINGLE",
                putio_file_id = targetFileId,
                fileName = targetFileName,
                download_url = downloadUrl,
                archiveMode = archiveMode
            )

            val added = calibreRepository.appendToAssembly(
                assemblyFileId = assemblyFileId,
                newItem = newItem,
                newFileIds = listOf(targetFileId),
                overrideTitle = overrideTitle,
                overrideAuthor = overrideAuthor,
                overrideUuid = overrideUuid,
                overrideTags = overrideTags,
                overrideProtected = overrideProtected,
            )
            _snackbarMessage.value = if (added) "File added to assembly"
                else "\"$targetFileName\" is already in this assembly"
            } finally {
                calibreRepository.clearAssemblyAppendPending(assemblyFileId)
            }
        }
    }

    // CONTRACT: ADD_BOOK_BATCH — merge framework. Resolves one PutioFile into the
    // AudiobookFile source-field shape the daemon expects (use_local/smb_path/download_url),
    // shared by both the flat (file/flatten) and grouped (subfolders-as-chapters) merge paths.
    // Falls through to uploadLocalFileIfNecessary for on-device local files (it no-ops and
    // returns file.id for files that are already remote), matching sendPdfPack's fuller
    // source resolution instead of the put.io-only fallback the original sendImagePdfPack had.
    private suspend fun resolveForMerge(
        file: PutioFile,
        putioToken: String,
        progressKey: Long,
        fileIndex: Int,
        totalFiles: Int,
        clearProgressOnSuccess: Boolean,
    ): AudiobookFile? {
        return when {
            file.isSynced -> {
                val localPath = calibreRepository.readStubLocalPath(file)
                if (localPath == null) {
                    _snackbarMessage.value = "Could not resolve local path for ${file.displayName} — stub may be missing. Please retry."
                    null
                } else {
                    AudiobookFile(file.syncedFileId, file.displayName, use_local = true, local_path = localPath)
                }
            }
            file.isLan -> {
                val conn = file.lanConnectionId?.let { lanFilesRepository.getConnectionById(it) }
                if (conn == null || file.lanPath == null) {
                    _snackbarMessage.value = "LAN connection info missing for ${file.name}"
                    null
                } else {
                    AudiobookFile(file.id, file.name, smb_path = buildUncPath(conn.host, conn.shareName, file.lanPath))
                }
            }
            else -> {
                val id = uploadLocalFileIfNecessary(file, putioToken, progressKey, fileIndex, totalFiles, clearProgressOnSuccess) ?: return null
                AudiobookFile(id, file.name, filesRepository.getDownloadUrl(putioToken, id))
            }
        }
    }

    // CONTRACT: ADD_BOOK_BATCH — merge framework (file trigger: flat, pre-selected/ordered files)
    //
    // When any file is on-device-local, a placeholder transfer (status=UPLOADING,
    // localUrisJson set) is created BEFORE uploading — same pattern the original
    // sendAudiobookPack used — so the transfer stays visible/resumable if Android kills the
    // app mid-upload (GlobalSyncViewModel's orphan detector resumes it via
    // restartOrphanedUpload). clearProgressOnSuccess is always false during that loop so the
    // progress key never disappears between files; clearing it mid-pack made the orphan
    // detector mistake the inter-file gap for a dead upload (the bug the original
    // sendAudiobookPack avoided that the other three engines inherited from sendPdfPack until
    // now).
    fun sendMergeFiles(
        type: String,
        fileName: String,
        files: List<PutioFile>,
        title: String,
        author: String,
        calibreBookUuid: String? = null,
        tags: String? = null,
        isProtected: Boolean = false,
        assembleBook: Boolean = false,
    ) {
        trackTransferPreparation { viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            val tempId = files.first().id

            if (files.any { it.isLocal }) {
                val localUrisJson = org.json.JSONArray(files.mapNotNull { it.localUri }).toString()
                calibreRepository.addMergeTransfer(
                    type = type, fileName = fileName,
                    files = files.map { it to AudiobookFile(it.id, it.name) },
                    title = title, author = author, googleAccount = googleAccount,
                    isUploading = true, localUrisJson = localUrisJson,
                    calibreBookUuid = calibreBookUuid, tags = tags, isProtected = isProtected,
                )

                val resolved = mutableListOf<AudiobookFile>()
                for ((index, file) in files.withIndex()) {
                    calibreRepository.updatePrepareProgress((index + 1) to files.size)
                    val r = resolveForMerge(file, putioToken, tempId, index + 1, files.size, clearProgressOnSuccess = false)
                    if (r == null) {
                        calibreRepository.updateUploadProgress(tempId, null)
                        calibreRepository.markPackUploadFailed(tempId, "Upload failed for ${file.name}")
                        return@launch
                    }
                    resolved.add(r)
                }
                calibreRepository.updateUploadProgress(tempId, null)

                if (assembleBook) {
                    calibreRepository.removeTransfer(tempId)
                    calibreRepository.addMergeTransfer(
                        type = type, fileName = fileName,
                        files = files.zip(resolved),
                        title = title, author = author, googleAccount = googleAccount,
                        assembleBook = true, calibreBookUuid = calibreBookUuid, tags = tags, isProtected = isProtected,
                    )
                } else {
                    calibreRepository.updateMergeAfterUpload(tempId, resolved, googleAccount)
                }
                _snackbarMessage.value = if (assembleBook) "Merge queued for assembly" else "Merge transfer requested"
                return@launch
            }

            val resolvedPairs = mutableListOf<Pair<PutioFile, AudiobookFile>>()
            for ((index, file) in files.withIndex()) {
                calibreRepository.updatePrepareProgress((index + 1) to files.size)
                val resolved = resolveForMerge(file, putioToken, tempId, index + 1, files.size, clearProgressOnSuccess = false) ?: return@launch
                resolvedPairs.add(file to resolved)
            }
            calibreRepository.updateUploadProgress(tempId, null)

            calibreRepository.addMergeTransfer(
                type = type,
                fileName = fileName,
                files = resolvedPairs,
                title = title,
                author = author,
                googleAccount = googleAccount,
                assembleBook = assembleBook,
                calibreBookUuid = calibreBookUuid,
                tags = tags,
                isProtected = isProtected,
            )
            _snackbarMessage.value = if (assembleBook) "Merge queued for assembly" else "Merge transfer requested"
        } }
    }

    // CONTRACT: ADD_BOOK_BATCH — merge framework (folder trigger: subfolders-as-chapters)
    fun sendMergeGroups(
        type: String,
        fileName: String,
        groups: List<MergeCandidateGroup>,
        title: String,
        author: String,
        calibreBookUuid: String? = null,
        tags: String? = null,
        isProtected: Boolean = false,
        assembleBook: Boolean = false,
    ) {
        trackTransferPreparation { viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val allFiles = groups.flatMap { it.files }
            if (allFiles.isEmpty()) {
                _snackbarMessage.value = "No files to merge"
                return@launch
            }
            val putioToken = settingsRepository.authTokenFlow.first()
            val tempId = allFiles.first().file.id
            // NOTE: unlike sendMergeFiles, grouped merges don't get the on-device-local-upload
            // placeholder/resume treatment — restartOrphanedUpload only knows how to re-derive
            // fileNames from a stored item's flat `files` list, not `groups`. An app-kill
            // mid-upload here would leave the transfer stuck rather than resuming. Narrow gap:
            // requires subfolders-as-chapters AND raw on-device files AND a kill mid-upload.
            var done = 0
            val resolvedGroups = mutableListOf<Pair<String, List<Pair<PutioFile, AudiobookFile>>>>()
            for (group in groups) {
                val resolvedPairs = mutableListOf<Pair<PutioFile, AudiobookFile>>()
                for (candidate in group.files) {
                    calibreRepository.updatePrepareProgress(done + 1 to allFiles.size)
                    val resolved = resolveForMerge(
                        candidate.file, putioToken,
                        progressKey = tempId,
                        fileIndex = done + 1,
                        totalFiles = allFiles.size,
                        clearProgressOnSuccess = false,
                    ) ?: return@launch
                    done++
                    resolvedPairs.add(candidate.file to resolved)
                }
                resolvedGroups.add(group.label to resolvedPairs)
            }
            calibreRepository.updateUploadProgress(tempId, null)

            calibreRepository.addMergeTransfer(
                type = type,
                fileName = fileName,
                groups = resolvedGroups,
                title = title,
                author = author,
                googleAccount = googleAccount,
                assembleBook = assembleBook,
                calibreBookUuid = calibreBookUuid,
                tags = tags,
                isProtected = isProtected,
            )
            _snackbarMessage.value = if (assembleBook) "Merge queued for assembly" else "Merge transfer requested"
        } }
    }

    // CONTRACT: ADD_BOOK_BATCH — merge framework. Resolves a flat file list or a chaptered
    // group list into one CalibreBatchItem + the put.io file IDs it covers, for
    // appendMergeToAssembly. Shared so that function doesn't duplicate the flat/grouped
    // resolve loop sendMergeFiles/sendMergeGroups already have.
    private suspend fun buildResolvedMergeItem(
        type: String, fileName: String, putioToken: String, progressKey: Long,
        files: List<PutioFile>?, groups: List<MergeCandidateGroup>?,
    ): Pair<CalibreBatchItem, List<Long>>? {
        if (groups != null) {
            val allFiles = groups.flatMap { it.files }
            if (allFiles.isEmpty()) return null
            var done = 0
            val resolvedGroups = mutableListOf<PackGroup>()
            val newIds = mutableListOf<Long>()
            for (group in groups) {
                val resolved = mutableListOf<AudiobookFile>()
                for (candidate in group.files) {
                    calibreRepository.updatePrepareProgress((done + 1) to allFiles.size)
                    val r = resolveForMerge(candidate.file, putioToken, progressKey, done + 1, allFiles.size, clearProgressOnSuccess = false) ?: return null
                    done++
                    resolved.add(r)
                    newIds.add(r.putio_file_id)
                }
                resolvedGroups.add(PackGroup(group.label, resolved))
            }
            if (allFiles.any { it.file.isLocal }) calibreRepository.updateUploadProgress(progressKey, null)
            return CalibreBatchItem(type = type, putio_file_id = newIds.first(), fileName = fileName, groups = resolvedGroups) to newIds
        }

        val list = files ?: return null
        if (list.isEmpty()) return null
        val resolved = mutableListOf<AudiobookFile>()
        val newIds = mutableListOf<Long>()
        for ((index, file) in list.withIndex()) {
            calibreRepository.updatePrepareProgress((index + 1) to list.size)
            val r = resolveForMerge(file, putioToken, progressKey, index + 1, list.size, clearProgressOnSuccess = false) ?: return null
            resolved.add(r)
            newIds.add(r.putio_file_id)
        }
        if (list.any { it.isLocal }) calibreRepository.updateUploadProgress(progressKey, null)
        return CalibreBatchItem(type = type, putio_file_id = newIds.first(), fileName = fileName, files = resolved) to newIds
    }

    // CONTRACT: ADD_BOOK_BATCH — merge framework. Appends a merge item into an existing pending
    // (not-yet-dispatched) assembly transfer — the "Assemble into fused PDF"-style mechanism,
    // generalized for any merge engine. Pass either `files` (flat) or `groups` (chaptered).
    // Which item (if any) in [assembly] a new [payloadType] pack batch would fold into, for the
    // "Pick Assembly" picker to filter candidates and preview what's already in each one.
    fun compatibleAssemblyItem(assembly: com.damarquez.putz.data.local.CalibreTransferEntity, payloadType: String) =
        calibreRepository.compatibleAssemblyItem(assembly, payloadType)

    fun appendMergeToAssembly(
        assemblyFileId: Long, type: String, fileName: String,
        files: List<PutioFile>? = null, groups: List<MergeCandidateGroup>? = null,
        overrideTitle: String? = null, overrideAuthor: String? = null,
        overrideUuid: String? = null, overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ) {
        trackTransferPreparation { viewModelScope.launch {
            calibreRepository.markAssemblyAppendPending(assemblyFileId)
            try {
                val putioToken = settingsRepository.authTokenFlow.first()
                val (newItem, newIds) = buildResolvedMergeItem(type, fileName, putioToken, assemblyFileId, files, groups)
                    ?: return@launch
                val added = calibreRepository.mergeIntoAssemblyItem(
                    assemblyFileId, newItem, newIds,
                    overrideTitle = overrideTitle, overrideAuthor = overrideAuthor,
                    overrideUuid = overrideUuid, overrideTags = overrideTags,
                    overrideProtected = overrideProtected,
                )
                _snackbarMessage.value = if (added) "Added to assembly" else "\"$fileName\" is already in this assembly"
            } finally {
                calibreRepository.clearAssemblyAppendPending(assemblyFileId)
            }
        } }
    }

    // Pending folder merge trigger, walking through "what content type" then "what process"
    // (flatten vs. subfolders-as-chapters) before the picker sheet shows. The whole folder tree
    // is scanned once, up front, so the content-type dialog can show per-type counts.
    private val _mergeChoiceState = MutableStateFlow<MergeChoiceState?>(null)
    val mergeChoiceState: StateFlow<MergeChoiceState?> = _mergeChoiceState.asStateFlow()

    fun openMergeProcessChoice(folder: PutioFile) {
        _mergeChoiceState.value = MergeChoiceState.Scanning(folder.name)
        viewModelScope.launch {
            try {
                val scan = scanMergeFolder(folder)
                _mergeChoiceState.value = MergeChoiceState.Ready(folder.name, scan)
            } catch (e: Exception) {
                _mergeChoiceState.value = MergeChoiceState.Error(folder.name, e.message ?: "Failed to scan folder")
            }
        }
    }

    fun chooseMergeContentType(type: MergeContentType) {
        val ready = _mergeChoiceState.value as? MergeChoiceState.Ready ?: return
        _mergeChoiceState.value = ready.copy(contentType = type)
    }

    fun dismissMergeProcessChoice() {
        _mergeChoiceState.value = null
    }

    private val _mergePickerState = MutableStateFlow<MergePickerState?>(null)
    val mergePickerState: StateFlow<MergePickerState?> = _mergePickerState.asStateFlow()

    // The content type used for the scan currently backing _mergePickerState — read at confirm
    // time to pick the right item type/fileName for sendMergeFiles/sendMergeGroups, since the
    // pending choice above is cleared as soon as the scan starts.
    private val _activeMergeContentType = MutableStateFlow<MergeContentType?>(null)
    val activeMergeContentType: StateFlow<MergeContentType?> = _activeMergeContentType.asStateFlow()

    fun startMergeFolderScan(mode: MergeProcessMode) {
        val ready = _mergeChoiceState.value as? MergeChoiceState.Ready ?: return
        val contentType = ready.contentType ?: return
        _mergeChoiceState.value = null
        _activeMergeContentType.value = contentType
        when (mode) {
            MergeProcessMode.FLATTEN -> {
                val files = ready.scan.flatCandidates(contentType)
                _mergePickerState.value = if (files.isEmpty())
                    MergePickerState.Error(ready.folderName, "No ${contentType.label.lowercase()} found in this folder")
                else MergePickerState.ReadyFlat(ready.folderName, files)
            }
            MergeProcessMode.SUBFOLDERS_AS_CHAPTERS -> {
                val groups = ready.scan.groupedCandidates(contentType)
                _mergePickerState.value = if (groups.isEmpty())
                    MergePickerState.Error(ready.folderName, "No subfolders with ${contentType.label.lowercase()} found in this folder")
                else MergePickerState.ReadyGrouped(ready.folderName, groups)
            }
        }
    }

    fun dismissMergePicker() {
        _mergePickerState.value = null
    }

    // Full (untyped) recursive scan of a merge folder trigger — classifies every file by
    // content type (rather than filtering by one) so the content-type dialog can show counts,
    // and its result is reused by startMergeFolderScan so the tree is only walked once.
    private suspend fun scanMergeFolder(folder: PutioFile): FolderScanResult {
        val token = settingsRepository.authTokenFlow.first()
        val result = mutableListOf<ScannedMergeFile>()
        var subfolderCount = 0
        val queue = ArrayDeque<Pair<PutioFile, String>>()
        queue.add(folder to "")
        while (queue.isNotEmpty()) {
            val (currentFolder, prefix) = queue.removeFirst()
            val children = when {
                currentFolder.isLocal -> currentFolder.localUri?.let { localFilesRepository.listLocalFolder(it).first() } ?: emptyList()
                currentFolder.isLan -> currentFolder.lanConnectionId?.let { lanFilesRepository.listDirectory(it, currentFolder.lanPath ?: "", includeAllFiles = true).last() } ?: emptyList()
                else -> filesRepository.listFiles(token, currentFolder.id).dataOrNull()?.first ?: emptyList()
            }
            for (child in children) {
                val childPath = if (prefix.isEmpty()) child.displayName else "$prefix/${child.displayName}"
                if (child.isFolder) {
                    subfolderCount++
                    queue.add(child to childPath)
                } else {
                    val contentType = MergeContentType.entries.firstOrNull { it.matches(child) }
                    result.add(ScannedMergeFile(child, childPath, contentType))
                }
            }
        }
        return FolderScanResult(result, subfolderCount)
    }

    fun openPutioArchive(file: PutioFile) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val url = filesRepository.getDownloadUrl(token, file.id)
            // CONTRACT: stub convention — fileId is the original file ID; stubFileId is the actual put.io ID of the stub
            _putioArchiveEvent.emit(PutioArchiveEvent(file.syncedFileId, file.id, file.displayName, url, file.size, file.parentId, file.isSynced))
        }
    }

    // Stub JSON's local_path/file_size, for the file-details dialog (stub names are often truncated in the UI)
    suspend fun readStubContent(file: PutioFile) = calibreRepository.readStubContent(file)

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    suspend fun checkBookExists(title: String, author: String): Long? {
        val dbFile = File(context.filesDir, "metadata.db")
        return calibreRepository.checkExists(dbFile, title, author)
    }

    suspend fun checkBookExistsByUuid(uuid: String): CalibreBookMatch? {
        val dbFile = File(context.filesDir, "metadata.db")
        return calibreRepository.checkExistsByUuid(dbFile, uuid)
    }

    fun replaceCover(file: PutioFile, title: String, author: String, calibreBookId: Long, calibreBookUuid: String? = null) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) return@launch
            val token = settingsRepository.authTokenFlow.first()

            val downloadUrl: String?
            val useLocal: Boolean
            val localPath: String?

            if (file.isSynced) {
                downloadUrl = null
                useLocal = true
                localPath = calibreRepository.readStubLocalPath(file)
            } else {
                downloadUrl = try {
                    filesRepository.getDownloadUrl(token, file.syncedFileId)
                } catch (e: Exception) {
                    _snackbarMessage.value = "Failed to get download URL: ${e.message}"
                    return@launch
                }
                useLocal = false
                localPath = null
            }

            calibreRepository.sendReplaceCoverRequest(
                putioFileId = file.syncedFileId,
                fileName = file.displayName,
                title = title,
                author = author,
                calibreBookId = calibreBookId,
                googleAccount = googleAccount,
                downloadUrl = downloadUrl,
                calibreBookUuid = calibreBookUuid,
                useLocal = useLocal,
                localPath = localPath,
            )
            _snackbarMessage.value = "Cover replacement request sent"
        }
    }

    private fun loadAccountInfo() {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = filesRepository.getAccountInfo(token)
            if (result is NetworkResult.Success) {
                _accountInfo.value = result.data
            }
        }
    }

    fun deleteFiles(files: List<PutioFile>) {
        viewModelScope.launch {
            val localFiles = files.filter { it.isLocal }
            val remoteIds = files.filter { !it.isLocal && !it.isLan }.map { it.id }

            if (localFiles.isNotEmpty()) {
                localFiles.forEach { local ->
                    if (local.localUri != null) {
                        localFilesRepository.detachOrHide(local.localUri)
                    } else {
                        localFilesRepository.detach(local.id)
                    }
                }
                _snackbarMessage.value = if (localFiles.size == 1) "Detached" else "${localFiles.size} items detached"
                loadFiles(isRefresh = true)
            }

            if (remoteIds.isNotEmpty()) {
                val token = settingsRepository.authTokenFlow.first()
                when (val result = filesRepository.deleteFiles(token, remoteIds)) {
                    is NetworkResult.Success -> {
                        _snackbarMessage.value = if (remoteIds.size == 1) "Deleted" else "${remoteIds.size} items deleted"
                        loadFiles(isRefresh = true)
                    }
                    is NetworkResult.Error -> {
                        _snackbarMessage.value = "Delete failed: ${result.message}"
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    fun renameFile(file: PutioFile, newName: String) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.renameFile(token, file.id, newName)) {
                is NetworkResult.Success -> loadFiles(isRefresh = true)
                is NetworkResult.Error -> _snackbarMessage.value = "Rename failed: ${result.message}"
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun toggleSearch() {
        _isSearchMode.value = !_isSearchMode.value
        if (!_isSearchMode.value) {
            _searchQuery.value = ""
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(searchResults = null, isSearching = false)
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        search(query)
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(searchResults = null, isSearching = false)
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(300) // Debounce

            val isLocalRoot = parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID
            val isLocalFolder = localUri != null || parentId <= com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_FOLDER_PREFIX_ID - 1000
            val isLanRoot = parentId == com.damarquez.putz.data.repository.LanFilesRepository.LAN_ROOT_ID
            val isLanBrowsing = lanConnectionId != -1L

            if (isLanRoot || isLanBrowsing) {
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    val immediateResults = current.files.filter { it.name.contains(query, ignoreCase = true) }
                    _uiState.value = current.copy(isSearching = false, searchResults = immediateResults)
                }
                return@launch
            }

            if (isLocalRoot || isLocalFolder) {
                // LOCAL SEARCH - STREAMED
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    // Instant shallow filter of already loaded files
                    val immediateResults = current.files.filter { it.name.contains(query, ignoreCase = true) }
                    _uiState.value = current.copy(
                        isSearching = true, 
                        searchResults = immediateResults
                    )
                }

                localFilesRepository.searchLocalFiles(query, localUri).collect { results ->
                    val cur = _uiState.value
                    if (cur is FilesUiState.Success) {
                        // Merge immediate results with background scan results (Set removes duplicates)
                        val merged = (cur.searchResults.orEmpty() + results).distinctBy { it.localUri ?: it.id }
                        _uiState.value = cur.copy(searchResults = merged, isSearching = true)
                    }
                }
                
                val curFinal = _uiState.value
                if (curFinal is FilesUiState.Success) {
                    _uiState.value = curFinal.copy(isSearching = false)
                }
                return@launch
            }

            // CLOUD SEARCH
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(isSearching = true)
            }

            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.searchFiles(token, query, parentId)) {
                is NetworkResult.Success -> {
                    val currentSuccess = _uiState.value as? FilesUiState.Success
                    if (currentSuccess != null) {
                        _uiState.value = currentSuccess.copy(
                            searchResults = result.data.sortedBy { it.name.lowercase() },
                            isSearching = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    // Handle error, maybe show snackbar
                    val currentSuccess = _uiState.value as? FilesUiState.Success
                    if (currentSuccess != null) {
                        _uiState.value = currentSuccess.copy(isSearching = false)
                    }
                    _snackbarMessage.value = "Search failed: ${result.message}"
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun signOut() = settingsRepository.clearAuth()

    fun openPlexFolderPicker() {
        viewModelScope.launch {
            val connectionId = settingsRepository.plexLibraryLanConnectionIdFlow.first()
            val rootPath = settingsRepository.plexLibraryLanPathFlow.first()
            if (connectionId == null) {
                _snackbarMessage.value = "Plex library LAN connection not configured in Settings"
                return@launch
            }
            val initialState = LanFolderPickerState(
                connectionId = connectionId,
                rootPath = rootPath,
                currentPath = rootPath,
                isLoading = true,
            )
            _plexPickerState.value = initialState
            loadPlexFolders(connectionId, rootPath)
        }
    }

    fun browsePlexFolder(folder: PutioFile) {
        val current = _plexPickerState.value ?: return
        val newPath = if (current.currentPath.isEmpty()) folder.name
            else "${current.currentPath}/${folder.name}"
        _plexPickerState.value = current.copy(
            pathStack = current.pathStack + current.currentPath,
            currentPath = newPath,
            folders = emptyList(),
            files = emptyList(),
            isLoading = true,
            error = null,
        )
        viewModelScope.launch { loadPlexFolders(current.connectionId, newPath) }
    }

    fun plexPickerNavigateUp() {
        val current = _plexPickerState.value ?: return
        if (!current.canNavigateUp) return
        val previousPath = current.pathStack.last()
        _plexPickerState.value = current.copy(
            pathStack = current.pathStack.dropLast(1),
            currentPath = previousPath,
            folders = emptyList(),
            files = emptyList(),
            isLoading = true,
            error = null,
        )
        viewModelScope.launch { loadPlexFolders(current.connectionId, previousPath) }
    }

    fun dismissPlexPicker() {
        _plexPickerState.value = null
    }

    fun sendToPlex(file: PutioFile, movieTitle: String, year: String, destPath: String, assembleMode: Boolean, createFolder: Boolean = true) {
        viewModelScope.launch {
            val account = googleAccount.value
            if (account.isBlank() && !assembleMode) {
                _snackbarMessage.value = "Google account not configured"
                return@launch
            }
            calibreRepository.createPlexAssembly(
                file = file,
                movieTitle = movieTitle,
                year = year,
                destPath = destPath,
                assembleMode = assembleMode,
                googleAccount = account,
                createFolder = createFolder,
            )
            _snackbarMessage.value = if (assembleMode) "Movie assembly created — add subtitles, then tap play" else "Plex transfer request sent"
        }
    }

    fun appendSubtitleToPlexAssembly(assemblyId: Long, file: PutioFile, language: String) {
        viewModelScope.launch {
            val error = calibreRepository.appendSubtitleToPlexAssembly(assemblyId, file, language)
            _snackbarMessage.value = error ?: "Subtitle (${language.uppercase()}) added to assembly"
        }
    }

    private val _movieBrowserState = MutableStateFlow<LanFolderPickerState?>(null)
    val movieBrowserState: StateFlow<LanFolderPickerState?> = _movieBrowserState.asStateFlow()

    fun openMovieBrowser() {
        viewModelScope.launch {
            val connectionId = settingsRepository.plexLibraryLanConnectionIdFlow.first()
            val rootPath = settingsRepository.plexLibraryLanPathFlow.first()
            if (connectionId == null) {
                _snackbarMessage.value = "Plex library LAN connection not configured in Settings"
                return@launch
            }
            _movieBrowserState.value = LanFolderPickerState(connectionId = connectionId, rootPath = rootPath, currentPath = rootPath, isLoading = true)
            loadMovieBrowserFiles(connectionId, rootPath)
        }
    }

    fun browseMovieBrowserFolder(folder: PutioFile) {
        val current = _movieBrowserState.value ?: return
        val newPath = if (current.currentPath.isEmpty()) folder.name else "${current.currentPath}/${folder.name}"
        _movieBrowserState.value = current.copy(pathStack = current.pathStack + current.currentPath, currentPath = newPath, folders = emptyList(), files = emptyList(), isLoading = true, error = null)
        viewModelScope.launch { loadMovieBrowserFiles(current.connectionId, newPath) }
    }

    fun movieBrowserNavigateUp() {
        val current = _movieBrowserState.value ?: return
        if (!current.canNavigateUp) return
        val previousPath = current.pathStack.last()
        _movieBrowserState.value = current.copy(pathStack = current.pathStack.dropLast(1), currentPath = previousPath, folders = emptyList(), files = emptyList(), isLoading = true, error = null)
        viewModelScope.launch { loadMovieBrowserFiles(current.connectionId, previousPath) }
    }

    fun dismissMovieBrowser() { _movieBrowserState.value = null }

    // Plexamp (music library) folder picker
    private val _plexampPickerState = MutableStateFlow<LanFolderPickerState?>(null)
    val plexampPickerState: StateFlow<LanFolderPickerState?> = _plexampPickerState.asStateFlow()

    fun openPlexampFolderPicker() {
        viewModelScope.launch {
            val connectionId = settingsRepository.plexampLibraryLanConnectionIdFlow.first()
            val rootPath = settingsRepository.plexampLibraryLanPathFlow.first()
            if (connectionId == null) {
                _snackbarMessage.value = "Plexamp library LAN connection not configured in Settings"
                return@launch
            }
            val initialState = LanFolderPickerState(
                connectionId = connectionId,
                rootPath = rootPath,
                currentPath = rootPath,
                isLoading = true,
            )
            _plexampPickerState.value = initialState
            loadPlexampFolders(connectionId, rootPath)
        }
    }

    fun browsePlexampFolder(folder: PutioFile) {
        val current = _plexampPickerState.value ?: return
        val newPath = if (current.currentPath.isEmpty()) folder.name else "${current.currentPath}/${folder.name}"
        _plexampPickerState.value = current.copy(
            pathStack = current.pathStack + current.currentPath,
            currentPath = newPath,
            folders = emptyList(),
            files = emptyList(),
            isLoading = true,
            error = null,
        )
        loadPlexampFolders(current.connectionId, newPath)
    }

    fun plexampPickerNavigateUp() {
        val current = _plexampPickerState.value ?: return
        if (!current.canNavigateUp) return
        val previousPath = current.pathStack.last()
        _plexampPickerState.value = current.copy(
            pathStack = current.pathStack.dropLast(1),
            currentPath = previousPath,
            folders = emptyList(),
            files = emptyList(),
            isLoading = true,
            error = null,
        )
        loadPlexampFolders(current.connectionId, previousPath)
    }

    fun dismissPlexampPicker() { _plexampPickerState.value = null }

    // (albumName, files) emitted when a folder scan for Plexamp completes; consumed once by FilesScreen
    private val _plexampFolderFiles = MutableStateFlow<Pair<String, List<PutioFile>>?>(null)
    val plexampFolderFiles: StateFlow<Pair<String, List<PutioFile>>?> = _plexampFolderFiles.asStateFlow()

    fun scanFolderForPlexamp(folder: PutioFile) {
        viewModelScope.launch {
            try {
                val audioFiles = fetchAudioFilesForPlexamp(folder)
                if (audioFiles.isEmpty()) {
                    _snackbarMessage.value = "No audio files found in '${folder.name}'"
                } else {
                    _plexampFolderFiles.value = folder.name to audioFiles
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed to scan folder: ${e.message}"
            }
        }
    }

    fun dismissPlexampFolderFiles() { _plexampFolderFiles.value = null }

    fun sendToPlexamp(files: List<PutioFile>, artistName: String, albumName: String, createFolder: Boolean, destPath: String) {
        viewModelScope.launch {
            val account = googleAccount.value
            if (account.isBlank()) {
                _snackbarMessage.value = "Google account not configured"
                return@launch
            }
            calibreRepository.sendToPlexamp(files, artistName, albumName, createFolder, destPath, account)
            _snackbarMessage.value = "Plexamp transfer request sent"
        }
    }

    private suspend fun fetchAudioFilesForPlexamp(folder: PutioFile): List<PutioFile> {
        val token = settingsRepository.authTokenFlow.first()
        val result = mutableListOf<PutioFile>()
        val queue = ArrayDeque<PutioFile>()
        queue.add(folder)
        while (queue.isNotEmpty()) {
            val currentFolder = queue.removeFirst()
            val children = when {
                currentFolder.isLocal -> currentFolder.localUri?.let { localFilesRepository.listLocalFolder(it).first() } ?: emptyList()
                currentFolder.isLan -> currentFolder.lanConnectionId?.let { lanFilesRepository.listDirectory(it, currentFolder.lanPath ?: "", includeAllFiles = true).last() } ?: emptyList()
                else -> filesRepository.listFiles(token, currentFolder.id).dataOrNull()?.first ?: emptyList()
            }
            for (child in children) {
                if (child.isFolder) queue.add(child)
                else if (MetadataUtils.isAudio(child.displayName)) result.add(child)
            }
        }
        result.sortBy { it.displayName }
        return result
    }

    private fun loadPlexampFolders(connectionId: Long, path: String) {
        viewModelScope.launch {
            try {
                val folders = lanFilesRepository.listDirectory(connectionId, path, includeAllFiles = false).last()
                val current = _plexampPickerState.value ?: return@launch
                _plexampPickerState.value = current.copy(
                    folders = folders.filter { it.isFolder },
                    files = emptyList(),
                    isLoading = false,
                    error = null,
                )
            } catch (e: Exception) {
                val current = _plexampPickerState.value ?: return@launch
                _plexampPickerState.value = current.copy(isLoading = false, error = e.message ?: "Failed to load folders")
            }
        }
    }

    fun sendAddSubtitleToMovie(subtitle: PutioFile, language: String, movieFolderPath: String, movieFileName: String) {
        viewModelScope.launch {
            val account = googleAccount.value
            if (account.isBlank()) {
                _snackbarMessage.value = "Google account not configured"
                return@launch
            }
            calibreRepository.sendAddSubtitleToMovieRequest(subtitle, language, movieFolderPath, movieFileName, account)
            _snackbarMessage.value = "Subtitle request sent"
        }
    }

    private suspend fun loadMovieBrowserFiles(connectionId: Long, path: String) {
        try {
            val allFiles = lanFilesRepository.listDirectory(connectionId, path, includeAllFiles = true).last()
            val current = _movieBrowserState.value ?: return
            _movieBrowserState.value = current.copy(
                folders = allFiles.filter { it.isFolder },
                files = allFiles.filter { !it.isFolder && MetadataUtils.isVideo(it.name) },
                isLoading = false,
                error = null,
            )
        } catch (e: Exception) {
            val current = _movieBrowserState.value ?: return
            _movieBrowserState.value = current.copy(isLoading = false, error = e.message ?: "Failed to load files")
        }
    }

    private suspend fun loadPlexFolders(connectionId: Long, path: String) {
        try {
            val allFiles = lanFilesRepository.listDirectory(connectionId, path, includeAllFiles = true).last()
            val current = _plexPickerState.value ?: return
            _plexPickerState.value = current.copy(
                folders = allFiles.filter { it.isFolder },
                files = allFiles.filter { !it.isFolder },
                isLoading = false,
                error = null,
            )
        } catch (e: Exception) {
            val current = _plexPickerState.value ?: return
            _plexPickerState.value = current.copy(isLoading = false, error = e.message ?: "Failed to load folders")
        }
    }

    private fun buildUncPath(host: String, shareName: String, lanPath: String): String {
        val normalized = lanPath.replace('/', '\\').trimStart('\\')
        return "\\\\$host\\$shareName\\$normalized"
    }

    fun onHighlightHandled() {
        // We can't easily modify SavedStateHandle once read,
        // but the Screen can use this to stop the highlighting effect.
    }

    fun openParentFolder(file: PutioFile) {
        viewModelScope.launch {
            val parentFolderId = file.parentId
            if (parentFolderId <= 0L) {
                _openParentFolderEvent.emit(Triple(0L, "Your Files", file.id))
                return@launch
            }
            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.getFile(token, parentFolderId)) {
                is NetworkResult.Success -> _openParentFolderEvent.emit(Triple(parentFolderId, result.data.name, file.id))
                else -> _openParentFolderEvent.emit(Triple(parentFolderId, "Folder", file.id))
            }
        }
    }
}
