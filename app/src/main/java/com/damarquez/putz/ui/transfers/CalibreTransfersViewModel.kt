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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CalibreTransfersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibreRepository: CalibreRepository,
    private val filesRepository: FilesRepository,
    private val settingsRepository: SettingsRepository,
    private val lanDaemonTransport: LanDaemonTransport,
) : ViewModel() {

    val transfers: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // CONTRACT: search matches against the transfer's full JSON (same serialization used by
    // "Copy JSON" in CalibreTransferItem), so a query matches ANY field — title, author, uuid,
    // status, error message, tags, batchData, etc. — not just a hand-picked subset.
    val filteredTransfers: StateFlow<List<CalibreTransferEntity>> = combine(
        transfers, _searchQuery
    ) { list, query ->
        if (query.isBlank()) return@combine list
        list.filter { Json.encodeToString(it).contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun batchAddTags(uuids: List<String>, tags: String, addToChain: Boolean = false) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            _snackbarMessage.value = if (addToChain) "Adding to chain..." else "Sending batch tag update..."
            try {
                calibreRepository.sendBatchAddTagsRequest(uuids, tags, account, addToChain = addToChain)
                _snackbarMessage.value = if (addToChain)
                    "Added to chain"
                else
                    "Batch tag request sent for ${uuids.size} book${if (uuids.size == 1) "" else "s"}"
            } catch (e: Exception) {
                _snackbarMessage.value = "Error: ${e.message}"
            }
        }
    }

    fun replaceCommentsFromClipboard(comments: String?, tags: String?, title: String, author: String, calibreBookId: Long, calibreBookUuid: String? = null, addToChain: Boolean = false) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch

            _snackbarMessage.value = if (addToChain) "Adding to chain..." else "Sending metadata update..."

            try {
                calibreRepository.sendUpdateCommentsRequest(
                    title = title,
                    author = author,
                    calibreBookId = calibreBookId,
                    comments = comments,
                    tags = tags,
                    googleAccount = account,
                    calibreBookUuid = calibreBookUuid,
                    addToChain = addToChain,
                )
                _snackbarMessage.value = if (addToChain) "Added to chain" else "Metadata update request sent"
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

    fun convertFormat(uuid: String, sourceFormat: String, targetFormat: String, title: String, author: String) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendConvertFormatRequest(
                title = title,
                author = author,
                calibreBookUuid = uuid,
                sourceFormat = sourceFormat,
                targetFormat = targetFormat,
                googleAccount = account,
            )
        }
    }

    fun protectBook(uuid: String, title: String, author: String, keepCover: Boolean = false, addToChain: Boolean = false) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendProtectBookRequest(
                title = title,
                author = author,
                calibreBookUuid = uuid,
                googleAccount = account,
                keepCover = keepCover,
                addToChain = addToChain,
            )
        }
    }

    fun unprotectBook(uuid: String, title: String, author: String, addToChain: Boolean = false) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            calibreRepository.sendUnprotectBookRequest(
                title = title,
                author = author,
                calibreBookUuid = uuid,
                googleAccount = account,
                addToChain = addToChain,
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

    fun replaceCoverFromClipboard(uri: android.net.Uri, title: String, author: String, calibreBookId: Long, calibreBookUuid: String? = null, addToChain: Boolean = false) {
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
                    calibreBookUuid = calibreBookUuid,
                    addToChain = addToChain,
                )
                _snackbarMessage.value = if (addToChain) "Added to chain" else "Cover replacement request sent"
            } else {
                _snackbarMessage.value = "Upload failed: ${(uploadResult as NetworkResult.Error).message}"
            }
        }
    }

    fun setAssemblyCoverFromClipboard(putioFileId: Long, uri: android.net.Uri) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            _snackbarMessage.value = "Uploading clipboard image..."
            val ok = calibreRepository.attachClipboardCoverToAssembly(putioFileId, uri, account)
            _snackbarMessage.value = if (ok) "Cover attached — will be set once this book is added"
                else "Failed to upload clipboard image"
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
                        // CONTRACT: CHAIN — a chain member queued behind slower earlier items can
                        // legitimately sit at REQUESTED far longer than 5 minutes with the daemon
                        // never having touched it yet; probing it would get back a premature FAILED
                        // (its expected end-state doesn't exist until the chain actually reaches it)
                        // and could even trigger an auto-retry that races the chain's own eventual
                        // run of the same item. Exempt any transfer that was placed as part of a
                        // chain from the stuck-transfer watchdog entirely.
                        val isStuck = transfer.chainId == null && (
                            transfer.status == CalibreTransferStatus.PENDING ||
                            transfer.status == CalibreTransferStatus.REQUESTED ||
                            transfer.status == CalibreTransferStatus.PROCESSING
                        )

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

    // CONTRACT: priority requests lane — promotes a not-yet-claimed transfer. Returns false (a
    // no-op) if the daemon already claimed/finished it, so the caller can tell the user it was
    // too late.
    suspend fun makeTransferPriority(fileId: Long): Boolean {
        val account = settingsRepository.googleTokenFlow.first()
        if (account.isBlank()) return false
        return calibreRepository.promoteTransferToPriority(fileId, account)
    }

    // CONTRACT: CHAIN
    val chainedTransfers: StateFlow<List<CalibreTransferEntity>> = calibreRepository.observeChainedTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val chainedCount: StateFlow<Int> = calibreRepository.observeChainedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun reorderChain(orderedFileIds: List<Long>) {
        viewModelScope.launch { calibreRepository.reorderChain(orderedFileIds) }
    }

    fun removeFromChain(fileId: Long) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            _snackbarMessage.value = "Removing from chain — sending now..."
            val result = calibreRepository.removeFromChain(fileId, account)
            _snackbarMessage.value = when (result) {
                is NetworkResult.Success -> "Sent"
                is NetworkResult.Error -> "Could not send: ${result.message}"
                else -> "Could not send"
            }
        }
    }

    fun placeChain() {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            _snackbarMessage.value = "Placing chain..."
            val result = calibreRepository.placeChain(account)
            _snackbarMessage.value = when (result) {
                is NetworkResult.Success -> "Chain placed"
                is NetworkResult.Error -> "Could not place chain: ${result.message}"
                else -> "Could not place chain"
            }
        }
    }

    // CalibreRepository.performFullSync() is the single implementation shared with Settings >
    // Libraries & Sync — both screens observe this same flow, so progress shows up wherever the
    // sync was triggered from and stays in sync (pun intended) if triggered from the other screen.
    val syncProgress: StateFlow<com.damarquez.putz.data.repository.SyncProgress?> = calibreRepository.syncProgress

    fun syncMetadata() {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isNotBlank()) {
                _isSyncing.value = true
                try {
                    val lanEnabled = settingsRepository.lanEnabledFlow.first()
                    val lanReachable = lanEnabled && lanDaemonTransport.isReachable()
                    val result = calibreRepository.performFullSync(account)

                    _snackbarMessage.value = when (result) {
                        is NetworkResult.Success -> if (lanEnabled && !lanReachable)
                            "Calibre metadata refreshed via Google Drive (daemon unreachable on local network)"
                        else
                            "Calibre metadata refreshed"
                        is NetworkResult.Error -> "Sync failed: ${result.message}"
                        else -> "Could not refresh Calibre metadata"
                    }
                } catch (e: Exception) {
                    // Without this, any uncaught exception anywhere in the chain above (a Drive
                    // hiccup, a bad response envelope, etc.) fell straight through to `finally` —
                    // the spinner stopped but no snackbar was ever set, so a failed sync looked
                    // identical to one that quietly did nothing.
                    android.util.Log.e("CalibreTransfersViewModel", "syncMetadata failed", e)
                    _snackbarMessage.value = "Sync failed: ${e.message ?: e.javaClass.simpleName}"
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
        ignoreCover: Boolean = false,
    ) {
        viewModelScope.launch {
            calibreRepository.updateAssemblyMetadata(fileId, title, author, tags, items, ignoreCover)
        }
    }

    fun removeTransfer(fileId: Long) {
        viewModelScope.launch {
            calibreRepository.removeTransfer(fileId)
        }
    }

    // CONTRACT: delete/detach runs in TransferDeleteService (not viewModelScope) so it survives
    // navigating away from this screen — viewModelScope is tied to this screen's NavBackStackEntry
    // and gets cancelled the instant it's popped, killing the delete mid-flight despite the
    // foreground notification still showing.
    fun deleteOrDetach(transfer: CalibreTransferEntity) {
        com.damarquez.putz.sync.TransferDeleteService.start(
            context, listOf(transfer.putioFileId), alsoDeleteFromPutio = true,
        )
    }

    fun deleteFromPutio(fileId: Long) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            calibreRepository.deleteFileFromPutio(token, fileId)
            // After successful delete from put.io, we can also remove from local list
            calibreRepository.removeTransfer(fileId)
        }
    }

    // CONTRACT: batch clear runs in TransferDeleteService (see deleteOrDetach above for why).
    fun clearGreenTransfers(alsoDeleteFromPutio: Boolean) {
        val green = transfers.value.filter {
            it.status == CalibreTransferStatus.COMPLETED && it.libraryVerified
        }
        if (green.isEmpty()) return
        com.damarquez.putz.sync.TransferDeleteService.start(
            context, green.map { it.putioFileId }, alsoDeleteFromPutio,
        )
    }
}
