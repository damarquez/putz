package com.damarquez.putz.ui.transfers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.FilesRepository
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
) : ViewModel() {

    val transfers: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val daemonStatus: StateFlow<String?> = settingsRepository.daemonStatusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uploadProgress: StateFlow<Map<Long, String>> = calibreRepository.uploadProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        startPolling()
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
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
        viewModelScope.launch {
            while (isActive) {
                val account = settingsRepository.googleTokenFlow.first()
                if (account.isNotBlank()) {
                    try {
                        calibreRepository.pollResponses(account)
                        calibreRepository.pollLibraryUpdates(account)
                        calibreRepository.pollHeartbeat(account)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

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

                        if (isFailedUpload && transfer.retryCount < 3 && now - transfer.lastUpdatedAt > 30 * 1000) {
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
                    calibreRepository.sendGlobalStatusProbe(account)
                    val dbFile = File(context.filesDir, "metadata.db")
                    val result = calibreRepository.syncMetadataDb(account, dbFile)
                    calibreRepository.pollHeartbeat(account)
                    
                    _snackbarMessage.value = when (result) {
                        is NetworkResult.Success -> "Calibre metadata refreshed"
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

    fun removeTransfer(fileId: Long) {
        viewModelScope.launch {
            calibreRepository.removeTransfer(fileId)
        }
    }

    fun deleteOrDetach(transfer: CalibreTransferEntity) {
        viewModelScope.launch {
            if (transfer.isTempUpload && transfer.sourceLocalUri != null) {
                localFilesRepository.detachOrHide(transfer.sourceLocalUri)
            } else {
                val token = settingsRepository.authTokenFlow.first()
                calibreRepository.deleteFileFromPutio(token, transfer.putioFileId)
            }
            calibreRepository.removeTransfer(transfer.putioFileId)
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
}
