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
import com.damarquez.putz.data.repository.AudiobookFile
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.FilesRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.Screen
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val filesRepository: FilesRepository,
    private val localFilesRepository: com.damarquez.putz.data.repository.LocalFilesRepository,
    private val lanFilesRepository: com.damarquez.putz.data.repository.LanFilesRepository,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val parentId: Long = savedStateHandle[Screen.Files.ARG_PARENT_ID] ?: 0L
    val folderName: String = savedStateHandle[Screen.Files.ARG_FOLDER_NAME] ?: "Your Files"
    val highlightFileId: Long = savedStateHandle[Screen.Files.ARG_HIGHLIGHT_ID] ?: -1L
    val localUri: String? = savedStateHandle[Screen.Files.ARG_LOCAL_URI]
    val lanConnectionId: Long = savedStateHandle[Screen.Files.ARG_LAN_CONNECTION_ID] ?: -1L
    val lanPath: String? = savedStateHandle[Screen.Files.ARG_LAN_PATH]

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val _previewIntent = MutableSharedFlow<Intent>()
    val previewIntent: SharedFlow<Intent> = _previewIntent.asSharedFlow()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    val googleAccount: StateFlow<String> = settingsRepository.googleTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val pendingAssemblies: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .map { transfers ->
            transfers.filter { it.status == CalibreTransferStatus.ASSEMBLED }
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
        viewModelScope.launch {
            // 1. Show loading
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(isPreviewLoading = true)
            }

            try {
                val token = settingsRepository.authTokenFlow.first()
                val url = filesRepository.getDownloadUrl(token, file.id)
                val targetFile = File(File(context.cacheDir, "previews"), file.name)
                
                // 2. Download to cache if doesn't exist
                if (!targetFile.exists()) {
                    val result = filesRepository.downloadToFile(url, targetFile)
                    if (result is NetworkResult.Error) {
                        _snackbarMessage.value = "Preview failed: ${result.message}"
                        return@launch
                    }
                }

                // 3. Prepare intent
                val uri = FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", targetFile)
                val extension = MimeTypeMap.getFileExtensionFromUrl(file.name)
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                _previewIntent.emit(intent)
            } catch (e: Exception) {
                _snackbarMessage.value = "Preview error: ${e.message}"
            } finally {
                // Hide loading
                val finalState = _uiState.value
                if (finalState is FilesUiState.Success) {
                    _uiState.value = finalState.copy(isPreviewLoading = false)
                }
            }
        }
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
                    _uiState.value = FilesUiState.Success(
                        files = augmentWithLocal(files),
                        parent = parent,
                    )
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
                    _uiState.value = FilesUiState.Success(files = augmentWithLocal(files), parent = parent)
                }
                is NetworkResult.Error -> {
                    _uiState.value = FilesUiState.Error(result.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
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
            listOf(localRoot, lanRoot) + apiFiles
        } else apiFiles

        return list.sortedWith(
            compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() }
        )
    }

    fun downloadFile(file: PutioFile) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val url = filesRepository.getDownloadUrl(token, file.id)
            
            val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(file.name)
                .setDescription("Downloading from put.io")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, file.name)
                .addRequestHeader("Authorization", "Bearer $token")

            downloadManager.enqueue(request)
            _snackbarMessage.value = "Download started: ${file.name}"
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
        if (!file.isLocal && !file.isLan) {
            android.util.Log.d("FilesViewModel", "File ${file.name} is remote, skipping upload.")
            return file.id
        }
        if (file.isLocal && file.localUri == null) return null
        if (file.isLan && (file.lanConnectionId == null || file.lanPath == null)) return null

        val localSize: Long = when {
            file.isLocal -> withContext(Dispatchers.IO) {
                val uri = android.net.Uri.parse(file.localUri!!)
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }
            file.isLan -> file.size
            else -> -1L
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
        val uploadTimeoutMs = 3 * 60 * 1000L
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
                    if (file.isLan) {
                        val inputStream = lanFilesRepository.openFileStream(file.lanConnectionId!!, file.lanPath!!)
                        filesRepository.uploadFileFromStream(
                            putioToken, tempFolderId!!, file.name, inputStream, localSize, progressCallback
                        )
                    } else {
                        val uri = android.net.Uri.parse(file.localUri!!)
                        filesRepository.uploadFile(
                            putioToken, tempFolderId!!, file.name, uri, context.contentResolver, progressCallback
                        )
                    }
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
                    _snackbarMessage.value = "Upload failed: ${(uploadResult as NetworkResult.Error).message}"
                    return null
                }
            }
        }
        calibreRepository.updateUploadProgress(progressKey, null)
        return null
    }

    fun sendToCalibre(file: PutioFile, title: String, author: String, archiveMode: String? = null, assembleBook: Boolean = false, isAltVersion: Boolean = false, calibreBookUuid: String? = null) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            
            if (file.isLocal || file.isLan) {
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
                                calibreBookUuid = calibreBookUuid
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
                    calibreBookUuid = calibreBookUuid
                )
                _snackbarMessage.value = if (assembleBook) "Book assembled" else "Transfer requested for $title"
            }
        }
    }

    fun sendAudiobookPack(files: List<PutioFile>, title: String, author: String, assembleBook: Boolean = false, isAltVersion: Boolean = false, calibreBookUuid: String? = null) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            val tempId = files.first().id
            
            val anyLocal = files.any { it.isLocal || it.isLan }
            if (anyLocal) {
                // Create placeholder — store local URIs so the upload can be restarted if the app is killed
                val localUrisJson = org.json.JSONArray(files.mapNotNull { it.localUri }).toString()
                calibreRepository.addAudiobookPackTransfer(
                    files = files.map { it to null },
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    assembleBook = assembleBook,
                    customFileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b",
                    calibreBookUuid = calibreBookUuid,
                    isUploading = true,
                    localUrisJson = localUrisJson,
                )

                // Triple: (put.io file ID after upload, download URL, original local file name)
                val uploadedFiles = mutableListOf<Triple<Long, String, String>>()
                android.util.Log.d("FilesViewModel", "Starting pack upload for ${files.size} files")
                for ((index, file) in files.withIndex()) {
                    android.util.Log.d("FilesViewModel", "Processing file ${index + 1}/${files.size}: ${file.name}")
                    // clearProgressOnSuccess=false keeps the key in the map between files so the
                    // orphan detector doesn't mistake the inter-file gap for a dead upload.
                    val id = uploadLocalFileIfNecessary(
                        file, putioToken,
                        progressKey = tempId,
                        fileIndex = index + 1,
                        totalFiles = files.size,
                        clearProgressOnSuccess = false,
                    )
                    if (id != null) {
                        val url = filesRepository.getDownloadUrl(putioToken, id)
                        uploadedFiles.add(Triple(id, url, file.name))
                        android.util.Log.d("FilesViewModel", "Upload successful for ${file.name}, ID: $id")
                    } else {
                        android.util.Log.e("FilesViewModel", "Upload failed for ${file.name}")
                        // Clear progress and leave the transfer visible as FAILED so the user
                        // knows what happened and the orphan detector can restart it.
                        calibreRepository.updateUploadProgress(tempId, null)
                        calibreRepository.markPackUploadFailed(tempId, "Upload failed for ${file.name}")
                        _snackbarMessage.value = "Upload failed for ${file.name}"
                        return@launch
                    }
                }
                // All files uploaded — clear the progress key before finishing.
                calibreRepository.updateUploadProgress(tempId, null)
                android.util.Log.d("FilesViewModel", "All ${files.size} files uploaded successfully")

                if (assembleBook) {
                    calibreRepository.removeTransfer(tempId)
                    // Pair each original PutioFile (correct name) with its uploaded put.io ID and URL
                    val uploadedPutioFiles = files.zip(uploadedFiles).map { (originalFile, triple) ->
                        originalFile.copy(id = triple.first) to triple.second
                    }
                    calibreRepository.addAudiobookPackTransfer(
                        files = uploadedPutioFiles,
                        title = title,
                        author = author,
                        googleAccount = googleAccount,
                        assembleBook = true,
                        customFileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b",
                        calibreBookUuid = calibreBookUuid
                    )
                } else {
                    calibreRepository.updateAudiobookAfterUpload(tempId, uploadedFiles, googleAccount)
                }
                _snackbarMessage.value = if (assembleBook) "Audiobook assembled" else "Audiobook transfer requested"
            } else {
                // Standard remote path
                val filesWithUrls = files.map { file ->
                    file to filesRepository.getDownloadUrl(putioToken, file.id)
                }

                calibreRepository.addAudiobookPackTransfer(
                    files = filesWithUrls,
                    title = title,
                    author = author,
                    googleAccount = googleAccount,
                    assembleBook = assembleBook,
                    customFileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b",
                    calibreBookUuid = calibreBookUuid
                )
                _snackbarMessage.value = if (assembleBook) "Audiobook assembled" else "Audiobook transfer requested"
            }
        }
    }

    fun appendToAssembly(assemblyFileId: Long, file: PutioFile, title: String, author: String, archiveMode: String? = null, isAltVersion: Boolean = false) {
        viewModelScope.launch {
            val putioToken = settingsRepository.authTokenFlow.first()
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

            calibreRepository.appendToAssembly(
                assemblyFileId = assemblyFileId,
                title = title,
                author = author,
                newItem = newItem,
                newFileIds = listOf(targetFileId)
            )
            _snackbarMessage.value = "File added to assembly: $title"
        }
    }

    fun appendAudiobookPackToAssembly(assemblyFileId: Long, files: List<PutioFile>, title: String, author: String, isAltVersion: Boolean = false) {
        viewModelScope.launch {
            val putioToken = settingsRepository.authTokenFlow.first()
            val filesWithUrls = files.map { file ->
                file to filesRepository.getDownloadUrl(putioToken, file.id)
            }

            val audioFiles = filesWithUrls.map { (file, url) ->
                AudiobookFile(file.id, file.name, url)
            }
            val fileName = if (isAltVersion) "Audiobook.m4b_bkp" else "Audiobook.m4b"
            val newItem = CalibreBatchItem(
                type = "PACK",
                putio_file_id = files.first().id,
                fileName = fileName,
                files = audioFiles
            )

            calibreRepository.appendToAssembly(
                assemblyFileId = assemblyFileId,
                title = title,
                author = author,
                newItem = newItem,
                newFileIds = files.map { it.id }
            )
            _snackbarMessage.value = "Audiobook pack added to assembly: $title"
        }
    }

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
            val account = accountInfo.value?.mail ?: return@launch
            val token = settingsRepository.authTokenFlow.first()
            val downloadUrl = filesRepository.getDownloadUrl(token, file.id)

            calibreRepository.sendReplaceCoverRequest(
                putioFileId = file.id,
                fileName = file.name,
                title = title,
                author = author,
                calibreBookId = calibreBookId,
                googleAccount = account,
                downloadUrl = downloadUrl,
                calibreBookUuid = calibreBookUuid
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

    fun onHighlightHandled() {
        // We can't easily modify SavedStateHandle once read, 
        // but the Screen can use this to stop the highlighting effect.
    }
}
