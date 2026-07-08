package com.damarquez.putz.ui.transfers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.PendingDeletionAction
import com.damarquez.putz.data.repository.FilesRepository
import com.damarquez.putz.data.transport.LanDaemonTransport
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CalibreTransfersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibreRepository: CalibreRepository,
    private val filesRepository: FilesRepository,
    private val localFilesRepository: com.damarquez.putz.data.repository.LocalFilesRepository,
    private val settingsRepository: SettingsRepository,
    private val lanDaemonTransport: LanDaemonTransport,
) : ViewModel() {

    val transfers: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val daemonStatus: StateFlow<String?> = settingsRepository.daemonStatusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uploadProgress: StateFlow<Map<Long, String>> = calibreRepository.uploadProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val pendingAssemblyAppends: StateFlow<Set<Long>> = calibreRepository.pendingAssemblyAppends
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _pendingDriveSyncConfirmation = MutableStateFlow(false)
    val pendingDriveSyncConfirmation: StateFlow<Boolean> = _pendingDriveSyncConfirmation

    fun requestSync() {
        viewModelScope.launch {
            val lanEnabled = settingsRepository.lanEnabledFlow.first()
            val lanReachable = lanEnabled && lanDaemonTransport.isReachable()
            if (lanEnabled && !lanReachable) {
                _pendingDriveSyncConfirmation.value = true
            } else {
                syncMetadata()
            }
        }
    }

    fun confirmDriveSync() {
        _pendingDriveSyncConfirmation.value = false
        syncMetadata()
    }

    fun dismissDriveSyncConfirmation() {
        _pendingDriveSyncConfirmation.value = false
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        startPolling()
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    fun batchAddTags(uuids: List<String>, tags: String) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            _snackbarMessage.value = "Sending batch tag update..."
            try {
                calibreRepository.sendBatchAddTagsRequest(uuids, tags, account)
                _snackbarMessage.value = "Batch tag request sent for ${uuids.size} book${if (uuids.size == 1) "" else "s"}"
            } catch (e: Exception) {
                _snackbarMessage.value = "Error: ${e.message}"
            }
        }
    }

    fun replaceCommentsFromClipboard(comments: String?, tags: String?, title: String, author: String, calibreBookId: Long, calibreBookUuid: String? = null) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch

            _snackbarMessage.value = "Sending metadata update..."

            try {
                calibreRepository.sendUpdateCommentsRequest(
                    title = title,
                    author = author,
                    calibreBookId = calibreBookId,
                    comments = comments,
                    tags = tags,
                    googleAccount = account,
                    calibreBookUuid = calibreBookUuid
                )
                _snackbarMessage.value = "Metadata update request sent"
            } catch (e: Exception) {
                _snackbarMessage.value = "Error: ${e.message}"
            }
        }
    }

    suspend fun checkBookExists(title: String, author: String): Long? {
        val dbFile = File(context.filesDir, "metadata.db")
        return calibreRepository.checkExists(dbFile, title, author)
    }

    suspend fun checkBookExistsByUuid(uuid: String): CalibreBookMatch? {
        val dbFile = File(context.filesDir, "metadata.db")
        return calibreRepository.checkExistsByUuid(dbFile, uuid)
    }

    fun generateCover(uuid: String, title: String, author: String) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendGenerateCoverRequest(
                title = title,
                author = author,
                calibreBookUuid = uuid,
                googleAccount = account,
            )
        }
    }

    fun protectBook(uuid: String, title: String, author: String) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendProtectBookRequest(
                title = title,
                author = author,
                calibreBookUuid = uuid,
                googleAccount = account,
            )
        }
    }

    fun unprotectBook(uuid: String, title: String, author: String) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendUnprotectBookRequest(
                title = title,
                author = author,
                calibreBookUuid = uuid,
                googleAccount = account,
            )
        }
    }

    fun setPageCount(uuid: String, pageCount: Int, title: String? = null, author: String? = null) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendSetPageCountRequest(
                calibreBookUuid = uuid,
                pageCount = pageCount,
                googleAccount = account,
                title = title,
                author = author,
            )
        }
    }

    fun sendEditMetadata(pending: com.damarquez.putz.data.repository.PendingEditMetadata) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendEditMetadataRequest(pending, account)
        }
    }

    fun handleDeletionAction(action: PendingDeletionAction) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            when (action) {
                is PendingDeletionAction.MarkBook ->
                    calibreRepository.sendMarkBookForDeletionRequest(action.uuid, account, action.title, action.author)
                is PendingDeletionAction.MarkFormats ->
                    calibreRepository.sendMarkFormatsForDeletionRequest(action.uuid, action.formats, account, action.title, action.author)
                is PendingDeletionAction.ConfirmDeleteBook ->
                    calibreRepository.sendConfirmDeleteBookRequest(action.uuid, account, action.title, action.author)
                is PendingDeletionAction.ConfirmDeleteFormats ->
                    calibreRepository.sendConfirmDeleteFormatsRequest(action.uuid, action.formats, account, action.title, action.author)
                is PendingDeletionAction.Cancel ->
                    calibreRepository.sendCancelDeletionRequest(action.uuid, account, action.title, action.author)
            }
        }
    }

    fun replaceCoverFromClipboard(uri: android.net.Uri, title: String, author: String, calibreBookId: Long, calibreBookUuid: String? = null) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            val token = settingsRepository.authTokenFlow.first()
            if (account.isBlank() || token.isBlank()) return@launch

            _snackbarMessage.value = "Uploading clipboard image..."

            // 1. Find or create .putz_attachments
            val listResult = filesRepository.listFiles(token, 0)
            val rootFiles = listResult.dataOrNull()?.first ?: emptyList<PutioFile>()
            var tempFolderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id

            if (tempFolderId == null) {
                val createResult = filesRepository.createFolder(token, 0, ".putz_attachments")
                if (createResult is NetworkResult.Success) {
                    tempFolderId = createResult.data.id
                } else {
                    _snackbarMessage.value = "Failed to create temp folder: ${(createResult as NetworkResult.Error).message}"
                    return@launch
                }
            }

            // 2. Upload the file
            val fileName = "clipboard_cover_${System.currentTimeMillis()}.jpg"
            val uploadResult = filesRepository.uploadFile(
                token,
                tempFolderId!!,
                fileName,
                uri,
                context.contentResolver
            )

            if (uploadResult is NetworkResult.Success) {
                val uploadedFile = uploadResult.data
                val downloadUrl = filesRepository.getDownloadUrl(token, uploadedFile.id)

                calibreRepository.sendReplaceCoverRequest(
                    putioFileId = uploadedFile.id,
                    fileName = uploadedFile.name,
                    title = title,
                    author = author,
                    calibreBookId = calibreBookId,
                    googleAccount = account,
                    downloadUrl = downloadUrl,
                    calibreBookUuid = calibreBookUuid
                )
                _snackbarMessage.value = "Cover replacement request sent"
            } else {
                _snackbarMessage.value = "Upload failed: ${(uploadResult as NetworkResult.Error).message}"
            }
        }
    }

    private fun startPolling() {
        // NOTE: pollResponses/pollLibraryUpdates/pollHeartbeat are NOT called here — that's
        // GlobalSyncViewModel's job, and it's a true app-wide singleton (see AppNavGraph). This
        // loop used to duplicate that work, running a second full poll cycle concurrently with
        // GlobalSyncViewModel's whenever this screen was open — which raced both loops against
        // each other (e.g. two threads both trying to delete the same already-handled response,
        // logged as spurious 404s) and doubled Drive API load for zero benefit. This loop now
        // only does the stuck-transfer watchdog logic that's unique to it.
        viewModelScope.launch {
            while (isActive) {
                val account = settingsRepository.googleTokenFlow.first()
                if (account.isNotBlank()) {
                    // Also check for stuck transfers (older than 5 mins) or failed GDrive uploads
                    val currentTransfers = calibreRepository.getTransfers().first()
                    val now = System.currentTimeMillis()
                    val activeProgressKeys = calibreRepository.uploadProgress.value.keys
                    currentTransfers.forEach { transfer ->
                        val isStuck = transfer.status == CalibreTransferStatus.PENDING ||
                                     transfer.status == CalibreTransferStatus.REQUESTED ||
                                     transfer.status == CalibreTransferStatus.PROCESSING

                        val isFailedUpload = transfer.status == CalibreTransferStatus.FAILED &&
                                             transfer.errorMessage?.contains("upload to GDrive", ignoreCase = true) == true

                        val neverDispatched = transfer.status == CalibreTransferStatus.PENDING &&
                                              transfer.gdriveRequestId == null &&
                                              !activeProgressKeys.contains(transfer.putioFileId) &&
                                              now - transfer.lastUpdatedAt > 30_000
                        if (neverDispatched) {
                            // Record was saved but GDrive upload never happened (e.g. app killed
                            // between DB write and dispatch). Retry dispatch from stored batchData.
                            calibreRepository.retryTransfer(transfer.putioFileId, account)
                        } else if (isFailedUpload && transfer.retryCount < 3 && now - transfer.lastUpdatedAt > 30_000) {
                            calibreRepository.retryTransfer(transfer.putioFileId, account)
                        } else if (isStuck && now - transfer.lastUpdatedAt > 5 * 60 * 1000) {
                            calibreRepository.sendProbeRequest(transfer.putioFileId, account)
                        }
                    }
                }
                delay(10_000) // Poll every 10 seconds
            }
        }
    }

    fun probeTransfer(fileId: Long) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isNotBlank()) {
                calibreRepository.sendProbeRequest(fileId, account)
            }
        }
    }

    fun retryTransfer(fileId: Long) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isNotBlank()) {
                _snackbarMessage.value = "Retrying transfer..."
                val result = calibreRepository.retryTransfer(fileId, account)
                _snackbarMessage.value = when (result) {
                    is NetworkResult.Success -> "Request resent successfully"
                    is NetworkResult.Error -> "Retry failed: ${result.message}"
                    else -> "Could not resend request"
                }
            }
        }
    }

    fun syncMetadata() {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isNotBlank()) {
                _isSyncing.value = true
                try {
                    _snackbarMessage.value = "Refreshing Calibre metadata..."
                    val lanEnabled = settingsRepository.lanEnabledFlow.first()
                    val lanReachable = lanEnabled && lanDaemonTransport.isReachable()
                    calibreRepository.sendGlobalStatusProbe(account)
                    val dbFile = File(context.filesDir, "metadata.db")
                    val result = calibreRepository.syncMetadataDb(account, dbFile)
                    if (result is NetworkResult.Success) {
                        calibreRepository.verifyCompletedTransfers(dbFile)
                    }
                    calibreRepository.pollHeartbeat(account)

                    _snackbarMessage.value = when (result) {
                        is NetworkResult.Success -> if (lanEnabled && !lanReachable)
                            "Calibre metadata refreshed via Google Drive (daemon unreachable on local network)"
                        else
                            "Calibre metadata refreshed"
                        is NetworkResult.Error -> "Sync failed: ${result.message}"
                        else -> "Could not refresh Calibre metadata"
                    }
                } finally {
                    _isSyncing.value = false
                }
            } else {
                _snackbarMessage.value = "No Google account configured"
            }
        }
    }

    fun updateAssemblyMetadata(
        fileId: Long,
        title: String,
        author: String,
        tags: String?,
        items: List<CalibreBatchItem>,
    ) {
        viewModelScope.launch {
            calibreRepository.updateAssemblyMetadata(fileId, title, author, tags, items)
        }
    }

    fun removeTransfer(fileId: Long) {
        viewModelScope.launch {
            calibreRepository.removeTransfer(fileId)
        }
    }

    fun deleteOrDetach(transfer: CalibreTransferEntity) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            com.damarquez.putz.sync.TransferDeleteService.start(context)
            try {
                if (transfer.isTempUpload && transfer.sourceLocalUri != null) {
                    localFilesRepository.detachOrHide(transfer.sourceLocalUri)
                    calibreRepository.deleteFileFromPutio(token, transfer.putioFileId)
                    calibreRepository.removeTransfer(transfer.putioFileId)
                    return@launch
                }
                // Only drop the local record if the put.io delete actually succeeded —
                // otherwise the files stay orphaned on put.io with no way to retry.
                val result = calibreRepository.deleteFileFromPutio(token, transfer.putioFileId)
                if (result is NetworkResult.Error) {
                    _snackbarMessage.value = "Failed to delete from put.io: ${result.message} (kept for retry)"
                } else {
                    calibreRepository.removeTransfer(transfer.putioFileId)
                }
            } finally {
                calibreRepository.updateDeleteProgress(null)
                com.damarquez.putz.sync.TransferDeleteService.stop(context)
            }
        }
    }

    fun deleteFromPutio(fileId: Long) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            calibreRepository.deleteFileFromPutio(token, fileId)
            // After successful delete from put.io, we can also remove from local list
            calibreRepository.removeTransfer(fileId)
        }
    }

    fun clearGreenTransfers(alsoDeleteFromPutio: Boolean) {
        viewModelScope.launch {
            val green = transfers.value.filter {
                it.status == CalibreTransferStatus.COMPLETED && it.libraryVerified
            }
            if (green.isEmpty()) return@launch
            val token = if (alsoDeleteFromPutio) settingsRepository.authTokenFlow.first() else ""
            if (alsoDeleteFromPutio) {
                com.damarquez.putz.sync.TransferDeleteService.start(context)
            }
            try {
                var failedCount = 0
                green.forEachIndexed { index, transfer ->
                    var deleteSucceeded = true
                    if (alsoDeleteFromPutio) {
                        if (transfer.isTempUpload && transfer.sourceLocalUri != null) {
                            calibreRepository.updateDeleteProgress(
                                com.damarquez.putz.data.repository.TransferDeleteProgress(
                                    message = "Detaching local file (transfer ${index + 1}/${green.size})",
                                    current = index + 1,
                                    total = green.size,
                                )
                            )
                            localFilesRepository.detachOrHide(transfer.sourceLocalUri)
                        } else {
                            val fileCount = transfer.parsedFileIds().size
                            calibreRepository.updateDeleteProgress(
                                com.damarquez.putz.data.repository.TransferDeleteProgress(
                                    message = "Deleting $fileCount file${if (fileCount == 1) "" else "s"} " +
                                        "(transfer ${index + 1}/${green.size})",
                                    current = index + 1,
                                    total = green.size,
                                )
                            )
                            val result = calibreRepository.deleteFileFromPutio(token, transfer.putioFileId)
                            if (result is NetworkResult.Error) {
                                deleteSucceeded = false
                                failedCount++
                                android.util.Log.w("CalibreTransfersViewModel",
                                    "Failed to delete put.io files for transfer ${transfer.putioFileId}: ${result.message}")
                            }
                        }
                    }
                    // Only drop the local record once the put.io delete actually succeeded —
                    // otherwise the transfer would vanish from the list with no way to retry it.
                    if (deleteSucceeded) {
                        calibreRepository.removeTransfer(transfer.putioFileId)
                    }
                }
                val clearedCount = green.size - failedCount
                _snackbarMessage.value = if (failedCount == 0) {
                    "$clearedCount transfer${if (clearedCount == 1) "" else "s"} cleared"
                } else {
                    "$clearedCount cleared, $failedCount failed to delete from put.io (kept for retry)"
                }
            } finally {
                calibreRepository.updateDeleteProgress(null)
                if (alsoDeleteFromPutio) {
                    com.damarquez.putz.sync.TransferDeleteService.stop(context)
                }
            }
        }
    }
}
