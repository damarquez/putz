package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.CalibreTransferDao
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.data.transport.DaemonTransport
import com.damarquez.putz.data.transport.ResponseEnvelope
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.util.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
// CONTRACT: ADD_BOOK_BATCH
@Serializable
data class CalibreBatchItem(
    val type: String, // "SINGLE", "PACK", "ARCHIVE", "ARCHIVE_ENTRY"
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
    val files: List<AudiobookFile>? = null, // For PACK
    val archiveMode: String? = null, // For ARCHIVE
    val use_local: Boolean? = null,  // When true the daemon uses the local synced copy; no download needed
    val smb_path: String? = null,    // When set the daemon reads directly from this UNC path; no download needed
    val archive_entry: String? = null, // For ARCHIVE_ENTRY: path of the entry within the archive file
)
// CONTRACT: ADD_BOOK_BATCH, probe pattern
@Serializable
data class CalibreBatchRequest(
    val action: String = "ADD_BOOK_BATCH",
    val putio_file_id: Long, // Anchor ID
    val title: String,
    val author: String,
    val items: List<CalibreBatchItem>,
    val is_probe: Boolean? = null,
    val calibre_book_id: Long? = null, // For REPLACE_COVER
    val calibre_book_uuid: String? = null, // For targeting existing book
    val comments: String? = null, // For UPDATE_COMMENTS
    val tags: String? = null, // For UPDATE_COMMENTS
)

// CONTRACT: ADD_BOOK_BATCH
@Serializable
data class AudiobookFile(
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
    val smb_path: String? = null,
    val use_local: Boolean? = null,
)

// CONTRACT: response schema, GLOBAL_STATUS_PROBE
@Serializable
data class CalibreResponse(
    val action: String,
    val putio_file_id: Long? = null,
    val status: String,
    val error: String? = null,
    val daemon_status: String? = null, // "IDLE" or "WORKING"
    val calibre_book_uuid: String? = null,
    val warnings: List<String>? = null,
)

data class CalibreBookMatch(
    val id: Long,
    val title: String,
    val author: String,
    val tags: String = "",
)

// CONTRACT: SEND_TO_PLEX
@Serializable
data class PlexAssemblyItem(
    val putio_file_id: Long,
    val fileName: String,
    val item_type: String = "MOVIE", // "MOVIE" or "SUBTITLE"
    val language: String? = null,
)

@Serializable
data class PlexBatchData(
    val movie_title: String,
    val year: String,
    val dest_path: String,
    val items: List<PlexAssemblyItem>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(s: String): PlexBatchData? = try { json.decodeFromString(s) } catch (_: Exception) { null }
    }
}

// CONTRACT: SEND_TO_PLEX
@Serializable
data class PlexTransferRequest(
    val action: String = "SEND_TO_PLEX",
    val putio_file_id: Long,
    val movie_title: String,
    val year: String,
    val dest_path: String,
    val items: List<PlexAssemblyItem>,
)

// CONTRACT: ADD_SUBTITLE_TO_MOVIE
@Serializable
data class PlexAddSubtitleRequest(
    val action: String = "ADD_SUBTITLE_TO_MOVIE",
    val putio_file_id: Long,
    val language: String,
    val movie_folder_path: String,
    val movie_file_name: String,
)

// CONTRACT: PRIORITY_PUTIO_SYNC
@Serializable
data class PrioritySyncRequest(
    val action: String = "PRIORITY_PUTIO_SYNC",
    val putio_file_id: Long,
)

// CONTRACT: FUSE_BOOKS
@Serializable
data class FuseFormatEntry(
    val format: String,
    val source_book_id: Long,
)

@Serializable
data class FuseMetadata(
    val title: String,
    val authors: String,
    val publisher: String? = null,
    val pubdate: String? = null,
    val series: String? = null,
    val series_index: Float? = null,
    val language: String? = null,
    val rating: Int? = null,
    val tags: List<String>? = null,
    val comments: String? = null,
)

@Serializable
data class FuseBooksRequest(
    val action: String = "FUSE_BOOKS",
    val putio_file_id: Long,
    val source_book_ids: List<Long>,
    val cover_source_book_id: Long?,
    val metadata: FuseMetadata,
    val formats: List<FuseFormatEntry>,
)

@Singleton
class CalibreRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val calibreTransferDao: CalibreTransferDao,
    private val gDriveManager: GDriveManager,
    private val daemonTransport: DaemonTransport,
    private val putioApiClient: PutioApiClient,
    private val secureStorage: SecureStorage,
    private val settingsRepository: com.damarquez.putz.settings.SettingsRepository,
) {
    private val _daemonStatus = MutableStateFlow<String?>(null)
    val daemonStatus = _daemonStatus.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Map<Long, String>>(emptyMap())
    val uploadProgress = _uploadProgress.asStateFlow()

    // Tracks the last time updateUploadProgress was called with a non-null value.
    // Used by the orphan detector to catch uploads stuck in a retry loop (where the
    // progress key IS present but no bytes have flowed for several minutes).
    private val _uploadProgressTimestamp = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val uploadProgressTimestamp = _uploadProgressTimestamp.asStateFlow()

    fun updateUploadProgress(transferId: Long, text: String?) {
        if (text == null) {
            _uploadProgress.value = _uploadProgress.value - transferId
            _uploadProgressTimestamp.value = _uploadProgressTimestamp.value - transferId
        } else {
            _uploadProgress.value = _uploadProgress.value + (transferId to text)
            _uploadProgressTimestamp.value = _uploadProgressTimestamp.value + (transferId to System.currentTimeMillis())
        }
    }

    private val json = Json { 
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    fun getTransfers(): Flow<List<CalibreTransferEntity>> = calibreTransferDao.getAllTransfers()

    // CONTRACT: UPDATE_COMMENTS
    suspend fun sendUpdateCommentsRequest(
        title: String,
        author: String,
        calibreBookId: Long,
        comments: String?,
        tags: String?,
        googleAccount: String,
        calibreBookUuid: String? = null,
    ) {
        // We use a fake putio_file_id for comments update as it doesn't involve a file
        val putioFileId = System.currentTimeMillis() 
        val request = CalibreBatchRequest(
            action = "UPDATE_COMMENTS",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_id = calibreBookId,
            calibre_book_uuid = calibreBookUuid,
            comments = comments,
            tags = tags,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_comments_$putioFileId.json", jsonStr)

        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Update comments for $title",
            title = "Comments for $title",
            author = author,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            isTempUpload = true,
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr
        )
        // NonCancellable: the GDrive upload already completed; the DB record must always
        // be written so the transfer is visible even if the calling scope navigates away.
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: PRIORITY_PUTIO_SYNC
    suspend fun sendPrioritySyncRequest(file: PutioFile, googleAccount: String): Boolean {
        val request = PrioritySyncRequest(putio_file_id = file.id)
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_priority_${file.id}.json", jsonStr)
        return gDriveId != null
    }

    suspend fun sendGlobalStatusProbe(googleAccount: String): Boolean {
        val request = mapOf("action" to "GLOBAL_STATUS_PROBE")
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_global_status.json", jsonStr)
        return gDriveId != null
    }

    suspend fun addTransfer(
        putioFileId: Long,
        fileName: String,
        title: String,
        author: String,
        googleAccount: String,
        downloadUrl: String?,
        archiveMode: String? = null,
        isTempUpload: Boolean = false,
        sourceLocalUri: String? = null,
        assembleBook: Boolean = false,
        calibreBookUuid: String? = null,
        isUploading: Boolean = false,
        localUrisJson: String? = null,
        useLocal: Boolean = false,
        smbPath: String? = null,
        archiveEntry: String? = null,
    ) {
        val initialItem = CalibreBatchItem(
            type = when {
                archiveEntry != null -> "ARCHIVE_ENTRY"
                archiveMode != null -> "ARCHIVE"
                else -> "SINGLE"
            },
            putio_file_id = putioFileId,
            fileName = fileName,
            download_url = downloadUrl,
            archiveMode = archiveMode,
            use_local = if (useLocal) true else null,
            smb_path = smbPath,
            archive_entry = archiveEntry,
        )
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = when {
                assembleBook -> CalibreTransferStatus.ASSEMBLED
                isUploading -> CalibreTransferStatus.UPLOADING
                else -> CalibreTransferStatus.PENDING
            },
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            isTempUpload = isTempUpload,
            sourceLocalUri = sourceLocalUri,
            batchData = json.encodeToString(listOf(initialItem)),
            calibreBookUuid = calibreBookUuid,
            localUrisJson = localUrisJson,
        )
        calibreTransferDao.insertTransfer(transfer)

        // useLocal/smbPath means we can dispatch immediately without a download URL
        if (assembleBook || isUploading || (downloadUrl == null && !useLocal && smbPath == null)) return

        // Immediately try to upload request
        val request = CalibreBatchRequest(
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = listOf(initialItem),
            calibre_book_uuid = calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$putioFileId.json", jsonStr)

        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr
            ))
        } else {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.FAILED,
                errorMessage = "Failed to upload to GDrive",
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr
            ))
        }
    }

    suspend fun updateTransferAfterUpload(fileId: Long, newPutioFileId: Long, downloadUrl: String, googleAccount: String) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        
        // Update batch data with new ID and URL
        val items = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: emptyList()
        
        val updatedItems = items.map { it.copy(putio_file_id = newPutioFileId, download_url = downloadUrl) }
        val updatedBatchData = json.encodeToString(updatedItems)

        val request = CalibreBatchRequest(
            putio_file_id = newPutioFileId,
            title = transfer.title,
            author = transfer.author,
            items = updatedItems,
            calibre_book_uuid = transfer.calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$newPutioFileId.json", jsonStr)

        calibreTransferDao.deleteTransfer(fileId) // Remove temp placeholder
        calibreTransferDao.insertTransfer(transfer.copy(
            putioFileId = newPutioFileId,
            allPutioFileIds = newPutioFileId.toString(),
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastUpdatedAt = System.currentTimeMillis(),
            lastRequestPayload = jsonStr,
            batchData = updatedBatchData
        ))
    }

    suspend fun addAudiobookPackTransfer(
        files: List<Pair<PutioFile, AudiobookFile>>,
        title: String,
        author: String,
        googleAccount: String,
        assembleBook: Boolean = false,
        customFileName: String? = null,
        calibreBookUuid: String? = null,
        isUploading: Boolean = false,
        localUrisJson: String? = null,
    ) {
        val primaryFileId = files.first().first.id
        val audioFiles = files.map { (_, audioFile) -> audioFile }
        val fileName = customFileName ?: "${files.size} MP3 files"
        val initialItem = CalibreBatchItem(
            type = "PACK",
            putio_file_id = primaryFileId,
            fileName = fileName,
            files = audioFiles
        )
        val transfer = CalibreTransferEntity(
            putioFileId = primaryFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = when {
                assembleBook -> CalibreTransferStatus.ASSEMBLED
                isUploading -> CalibreTransferStatus.UPLOADING
                else -> CalibreTransferStatus.PENDING
            },
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = audioFiles.joinToString(",") { it.putio_file_id.toString() },
            batchData = json.encodeToString(listOf(initialItem)),
            calibreBookUuid = calibreBookUuid,
            localUrisJson = localUrisJson,
        )
        calibreTransferDao.insertTransfer(transfer)

        if (assembleBook || isUploading || audioFiles.any { it.download_url == null && it.smb_path == null && it.use_local != true }) return

        val request = CalibreBatchRequest(
            putio_file_id = primaryFileId,
            title = title,
            author = author,
            items = listOf(initialItem),
            calibre_book_uuid = calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$primaryFileId.json", jsonStr)

        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr
            ))
        } else {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.FAILED,
                errorMessage = "Failed to upload to GDrive",
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr
            ))
        }
    }

    suspend fun updateAudiobookAfterUpload(tempId: Long, audioFiles: List<AudiobookFile>, googleAccount: String) {
        val transfer = calibreTransferDao.getTransferById(tempId) ?: return

        val newPrimaryId = audioFiles.first().putio_file_id
        val items = listOf(CalibreBatchItem(
            type = "PACK",
            putio_file_id = newPrimaryId,
            fileName = transfer.fileName,
            files = audioFiles
        ))

        val request = CalibreBatchRequest(
            putio_file_id = newPrimaryId,
            title = transfer.title,
            author = transfer.author,
            items = items,
            calibre_book_uuid = transfer.calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$newPrimaryId.json", jsonStr)

        calibreTransferDao.deleteTransfer(tempId)
        calibreTransferDao.insertTransfer(transfer.copy(
            putioFileId = newPrimaryId,
            allPutioFileIds = audioFiles.joinToString(",") { it.putio_file_id.toString() },
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastUpdatedAt = System.currentTimeMillis(),
            lastRequestPayload = jsonStr,
            batchData = json.encodeToString(items)
        ))
    }

    suspend fun appendToAssembly(
        assemblyFileId: Long,
        title: String,
        author: String,
        newItem: CalibreBatchItem,
        newFileIds: List<Long>,
    ): Boolean {
        val transfer = calibreTransferDao.getTransferById(assemblyFileId) ?: return false
        val currentItems = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: emptyList()

        val existingFileNames = currentItems.flatMap { item ->
            if (item.type == "PACK") item.files?.map { it.fileName } ?: listOf(item.fileName)
            else listOf(item.fileName)
        }.toSet()
        val incomingFileNames = if (newItem.type == "PACK")
            newItem.files?.map { it.fileName } ?: listOf(newItem.fileName)
        else listOf(newItem.fileName)
        if (incomingFileNames.any { it in existingFileNames }) return false

        val updatedItems = currentItems + newItem
        val updatedIds = (transfer.parsedFileIds() + newFileIds).distinct()

        calibreTransferDao.updateTransfer(transfer.copy(
            title = title,
            author = author,
            batchData = json.encodeToString(updatedItems),
            allPutioFileIds = updatedIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = null,
        ))
        return true
    }

    suspend fun setTransferErrorMessage(fileId: Long, message: String?) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        calibreTransferDao.updateTransfer(transfer.copy(errorMessage = message))
    }

    suspend fun getPendingAssemblies(): List<CalibreTransferEntity> {
        return calibreTransferDao.getAllTransfers().first().filter { 
            it.status == CalibreTransferStatus.ASSEMBLED 
        }
    }

    suspend fun syncMetadataDb(googleAccount: String, destination: File): NetworkResult<Unit> {
        val success = daemonTransport.downloadMetadataDb(googleAccount, destination)
        if (success) {
            daemonTransport.getLibraryVersion(googleAccount)?.let { timestamp ->
                settingsRepository.saveLastSyncTimestamp(timestamp)
                settingsRepository.saveLibraryHasUpdates(false)
            }
        }
        return if (success) NetworkResult.Success(Unit) else NetworkResult.Error("Download failed")
    }

    suspend fun pollLibraryUpdates(googleAccount: String) {
        val remoteTimestamp = daemonTransport.getLibraryVersion(googleAccount) ?: return
        val localTimestamp = settingsRepository.lastSyncTimestampFlow.first()
        if (localTimestamp == 0L) {
            settingsRepository.saveLastSyncTimestamp(remoteTimestamp)
        } else if (remoteTimestamp != localTimestamp) {
            settingsRepository.saveLibraryHasUpdates(true)
        }
    }

    suspend fun pollHeartbeat(googleAccount: String) {
        val heartbeat = daemonTransport.getHeartbeat(googleAccount) ?: return
        _daemonStatus.value = heartbeat.status
        settingsRepository.saveDaemonStatus(heartbeat.status)
    }

    suspend fun pollResponses(googleAccount: String) {
        val envelopes = daemonTransport.pollResponses(googleAccount)
        envelopes.forEach { envelope ->
            try {
                val response = json.decodeFromString<CalibreResponse>(envelope.content)

                if (response.action == "GLOBAL_STATUS_PROBE") {
                    _daemonStatus.value = response.daemon_status
                    settingsRepository.saveDaemonStatus(response.daemon_status)
                } else if (response.putio_file_id != null) {
                    val transfer = calibreTransferDao.getTransferById(response.putio_file_id)
                    if (transfer != null) {
                        val newStatus = when (response.status.uppercase()) {
                            "PROCESSING" -> CalibreTransferStatus.PROCESSING
                            "COMPLETED" -> CalibreTransferStatus.COMPLETED
                            "FAILED" -> CalibreTransferStatus.FAILED
                            else -> transfer.status
                        }

                        val isNewerStatus = newStatus.ordinal > transfer.status.ordinal
                        val isSameStatusFailure = newStatus == CalibreTransferStatus.FAILED && transfer.status == CalibreTransferStatus.FAILED

                        if (isNewerStatus || isSameStatusFailure) {
                            calibreTransferDao.updateTransfer(transfer.copy(
                                status = newStatus,
                                errorMessage = response.error,
                                calibreBookUuid = if (newStatus == CalibreTransferStatus.COMPLETED && response.calibre_book_uuid != null) response.calibre_book_uuid else transfer.calibreBookUuid,
                                warnings = if (newStatus == CalibreTransferStatus.COMPLETED) response.warnings?.joinToString("\n")?.takeIf { it.isNotBlank() } else transfer.warnings,
                                lastUpdatedAt = System.currentTimeMillis()
                            ))

                            if (newStatus == CalibreTransferStatus.COMPLETED && transfer.isTempUpload) {
                                val token = secureStorage.authTokenFlow.value
                                if (token.isNotBlank()) {
                                    deleteFileFromPutio(token, transfer.putioFileId)
                                }
                            }

                            if (newStatus == CalibreTransferStatus.FAILED && transfer.transferType != "FUSION" && response.error?.contains("not found", ignoreCase = true) == true) {
                                if (transfer.retryCount < 3) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val delayMs = Random.nextLong(2000, 60000)
                                        delay(delayMs)
                                        retryTransfer(transfer.putioFileId, googleAccount)
                                    }
                                }
                            }
                        } else {
                            calibreTransferDao.updateTransfer(transfer.copy(
                                lastUpdatedAt = System.currentTimeMillis()
                            ))
                        }
                    }
                }
                daemonTransport.acknowledgeResponse(googleAccount, envelope)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun createPlexAssembly(
        file: PutioFile,
        movieTitle: String,
        year: String,
        destPath: String,
        assembleMode: Boolean,
        googleAccount: String,
    ) {
        val displayName = file.displayName
        val movieItem = PlexAssemblyItem(putio_file_id = file.id, fileName = displayName, item_type = "MOVIE")
        val batchData = PlexBatchData(movie_title = movieTitle, year = year, dest_path = destPath, items = listOf(movieItem))
        val folderLabel = if (year.isNotBlank()) "$movieTitle ($year)" else movieTitle

        val transfer = CalibreTransferEntity(
            putioFileId = file.id,
            fileName = displayName,
            title = folderLabel,
            author = destPath.ifBlank { "Plex root" },
            status = if (assembleMode) CalibreTransferStatus.ASSEMBLED else CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = file.id.toString(),
            transferType = "PLEX",
            batchData = json.encodeToString(batchData),
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
        if (assembleMode) return

        val request = plexRequestFromBatchData(batchData, file.id)
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_plex_${file.id}.json", jsonStr)
        withContext(NonCancellable) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
                gdriveRequestId = gDriveId,
                errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
                lastRequestPayload = jsonStr,
            ))
        }
    }

    suspend fun appendSubtitleToPlexAssembly(
        assemblyFileId: Long,
        subtitle: PutioFile,
        language: String,
    ): String? {
        val transfer = calibreTransferDao.getTransferById(assemblyFileId) ?: return "Assembly not found"
        if (transfer.transferType != "PLEX") return "Not a Plex assembly"
        val batchData = transfer.batchData?.let {
            try { json.decodeFromString<PlexBatchData>(it) } catch (e: Exception) { return "Could not parse assembly data: ${e.message}" }
        } ?: return "No batch data"

        val existingLanguages = batchData.items.filter { it.item_type == "SUBTITLE" }.mapNotNull { it.language }.toSet()
        if (language in existingLanguages) return "This language is already in the assembly"

        val newItem = PlexAssemblyItem(putio_file_id = subtitle.id, fileName = subtitle.displayName, item_type = "SUBTITLE", language = language)
        val updated = batchData.copy(items = batchData.items + newItem)
        val updatedIds = (transfer.parsedFileIds() + subtitle.id).distinct()
        calibreTransferDao.updateTransfer(transfer.copy(
            batchData = json.encodeToString(updated),
            allPutioFileIds = updatedIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis(),
        ))
        return null
    }

    suspend fun sendAddSubtitleToMovieRequest(
        subtitle: PutioFile,
        language: String,
        movieFolderPath: String,
        movieFileName: String,
        googleAccount: String,
    ) {
        val request = PlexAddSubtitleRequest(
            putio_file_id = subtitle.id,
            language = language,
            movie_folder_path = movieFolderPath,
            movie_file_name = movieFileName,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_subtitle_${subtitle.id}.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = subtitle.id,
            fileName = subtitle.displayName,
            title = "Add subtitle → ${movieFileName.substringBeforeLast('.')}",
            author = movieFolderPath.ifBlank { "Plex" },
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = subtitle.id.toString(),
            transferType = "PLEX",
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    private fun plexRequestFromBatchData(batchData: PlexBatchData, anchorFileId: Long) = PlexTransferRequest(
        putio_file_id = anchorFileId,
        movie_title = batchData.movie_title,
        year = batchData.year,
        dest_path = batchData.dest_path,
        items = batchData.items,
    )

    private suspend fun retryPlexTransfer(transfer: CalibreTransferEntity, googleAccount: String): NetworkResult<Unit> {
        val payload = transfer.lastRequestPayload ?: run {
            val batchData = transfer.batchData?.let {
                try { json.decodeFromString<PlexBatchData>(it) } catch (e: Exception) {
                    return NetworkResult.Error("Could not parse Plex assembly data")
                }
            } ?: return NetworkResult.Error("No batch data for Plex transfer")
            json.encodeToString(plexRequestFromBatchData(batchData, transfer.putioFileId))
        }
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_plex_${transfer.putioFileId}.json", payload)
        return if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                retryCount = if (transfer.status == CalibreTransferStatus.FAILED) transfer.retryCount + 1 else transfer.retryCount,
                errorMessage = null,
                lastRequestPayload = payload,
            ))
            NetworkResult.Success(Unit)
        } else {
            NetworkResult.Error("Could not upload Plex request to Google Drive")
        }
    }

    // CONTRACT: FUSE_BOOKS
    suspend fun sendFuseBooksRequest(
        request: FuseBooksRequest,
        displayTitle: String,
        googleAccount: String,
    ) {
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(
            googleAccount,
            "req_fuse_${request.putio_file_id}.json",
            jsonStr,
        )
        val transfer = CalibreTransferEntity(
            putioFileId = request.putio_file_id,
            fileName = "Fusing ${request.source_book_ids.size} books",
            title = displayTitle,
            author = request.metadata.authors,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = request.putio_file_id.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            transferType = "FUSION",
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    suspend fun checkExistsByUuid(dbFile: File, uuid: String): CalibreBookMatch? = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext null
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val query = """
                    SELECT books.id, books.title, authors.name FROM books 
                    JOIN books_authors_link ON books.id = books_authors_link.book 
                    JOIN authors ON authors.id = books_authors_link.author 
                    WHERE books.uuid = ?
                """.trimIndent()
                
                db.rawQuery(query, arrayOf(uuid)).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val bookId = cursor.getLong(0)
                        return@withContext CalibreBookMatch(
                            id = bookId,
                            title = cursor.getString(1),
                            author = cursor.getString(2),
                            tags = getBookTags(db, bookId),
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getBookTags(db: android.database.sqlite.SQLiteDatabase, bookId: Long): String {
        val query = """
            SELECT tags.name FROM tags
            JOIN books_tags_link ON tags.id = books_tags_link.tag
            WHERE books_tags_link.book = ?
            ORDER BY tags.name COLLATE NOCASE
        """.trimIndent()
        return db.rawQuery(query, arrayOf(bookId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }.joinToString(", ")
        }
    }

    private fun normalize(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").lowercase()
    }

    suspend fun checkExists(dbFile: File, title: String, author: String): Long? = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext null
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                // First, try direct search with accents
                val query = """
                    SELECT books.id, books.title, authors.name FROM books 
                    JOIN books_authors_link ON books.id = books_authors_link.book 
                    JOIN authors ON authors.id = books_authors_link.author 
                """.trimIndent()
                
                db.rawQuery(query, null).use { cursor ->
                    val normTitle = normalize(title)
                    val normAuthor = normalize(author)

                    if (cursor.moveToFirst()) {
                        do {
                            val id = cursor.getLong(0)
                            val dbTitle = cursor.getString(1)
                            val dbAuthor = cursor.getString(2)
                            
                            val normDbTitle = normalize(dbTitle)
                            val normDbAuthor = normalize(dbAuthor)
                            
                            // Check if normalized search terms are contained within normalized DB values
                            if (normDbTitle.contains(normTitle) && (author.isBlank() || normDbAuthor.contains(normAuthor))) {
                                return@withContext id
                            }
                        } while (cursor.moveToNext())
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun sendReplaceCoverRequest(
        putioFileId: Long,
        fileName: String,
        title: String,
        author: String,
        calibreBookId: Long,
        googleAccount: String,
        downloadUrl: String,
        calibreBookUuid: String? = null,
    ) {
        val item = CalibreBatchItem(
            type = "SINGLE",
            putio_file_id = putioFileId,
            fileName = fileName,
            download_url = downloadUrl,
        )
        val request = CalibreBatchRequest(
            action = "REPLACE_COVER",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = listOf(item),
            calibre_book_id = calibreBookId,
            calibre_book_uuid = calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_cover_$putioFileId.json", jsonStr)

        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = fileName,
            title = "Cover for $title",
            author = author,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = json.encodeToString(listOf(item)),
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr
        )
        // NonCancellable: the GDrive upload already completed; the DB record must always
        // be written so the transfer is visible even if the calling scope navigates away.
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    suspend fun sendProbeRequest(fileId: Long, googleAccount: String): Boolean {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return false

        // If we have a GDrive request ID, check if it still exists
        transfer.gdriveRequestId?.let { requestId ->
            if (gDriveManager.checkFileExists(googleAccount, requestId)) {
                // File still exists, daemon hasn't picked it up. Just update timestamp to wait longer.
                calibreTransferDao.updateTransfer(transfer.copy(
                    lastUpdatedAt = System.currentTimeMillis()
                ))
                return false
            }
        }
        
        val items = if (!transfer.batchData.isNullOrBlank()) {
            json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData).map { item ->
                when (item.type) {
                    "PACK" -> item.copy(files = item.files?.map { it.copy(download_url = null) })
                    else -> item.copy(download_url = null)
                }
            }
        } else {
            val ids = transfer.parsedFileIds()
            if (ids.size > 1) {
                val audioFiles = ids.map { id -> AudiobookFile(id, "PROBE", null) }
                listOf(CalibreBatchItem("PACK", transfer.putioFileId, transfer.fileName, files = audioFiles))
            } else {
                listOf(CalibreBatchItem(
                    if (MetadataUtils.isArchive(transfer.fileName)) "ARCHIVE" else "SINGLE",
                    transfer.putioFileId,
                    transfer.fileName,
                    null
                ))
            }
        }

        val originalRequest = transfer.lastRequestPayload?.let {
            try { json.decodeFromString<CalibreBatchRequest>(it) } catch (e: Exception) { null }
        }

        val request = CalibreBatchRequest(
            action = originalRequest?.action ?: "ADD_BOOK_BATCH",
            putio_file_id = transfer.putioFileId,
            title = transfer.title,
            author = transfer.author,
            items = items,
            is_probe = true,
            calibre_book_id = originalRequest?.calibre_book_id,
            calibre_book_uuid = transfer.calibreBookUuid
        )

        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_probe_${transfer.putioFileId}.json", jsonStr)
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                lastUpdatedAt = System.currentTimeMillis()
            ))
            return true
        }
        return false
    }

    suspend fun retryTransfer(fileId: Long, googleAccount: String): NetworkResult<Unit> {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return NetworkResult.Error("Transfer not found")

        if (transfer.transferType == "PLEX") return retryPlexTransfer(transfer, googleAccount)

        val payload = transfer.lastRequestPayload ?: run {
            // Reconstruct if missing (for legacy transfers created before lastRequestPayload was added)
            val items = try {
                if (!transfer.batchData.isNullOrBlank()) {
                    json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData)
                } else {
                    // Fallback for very old single-file transfers
                    listOf(CalibreBatchItem(
                        type = if (MetadataUtils.isArchive(transfer.fileName)) "ARCHIVE" else "SINGLE",
                        putio_file_id = transfer.putioFileId,
                        fileName = transfer.fileName,
                        download_url = null // Note: retry might fail if daemon hasn't downloaded it yet and URL is null
                    ))
                }
            } catch (e: Exception) {
                return NetworkResult.Error("Could not reconstruct request: ${e.message}")
            }

            val action = when {
                transfer.fileName.startsWith("Update comments for") -> "UPDATE_COMMENTS"
                transfer.title.startsWith("Cover for") -> "REPLACE_COVER"
                else -> "ADD_BOOK_BATCH"
            }

            if (action == "UPDATE_COMMENTS" && transfer.lastRequestPayload == null) {
                return NetworkResult.Error("Original comment data was not saved and cannot be reconstructed")
            }

            val request = CalibreBatchRequest(
                action = action,
                putio_file_id = transfer.putioFileId,
                title = transfer.title,
                author = transfer.author,
                items = items,
                calibre_book_uuid = transfer.calibreBookUuid
            )
            json.encodeToString(request)
        }

        android.util.Log.d("CalibreRepository", "Retrying transfer $fileId for $googleAccount")
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_${transfer.putioFileId}.json", payload)
        
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                retryCount = if (transfer.status == CalibreTransferStatus.FAILED) transfer.retryCount + 1 else transfer.retryCount,
                errorMessage = null,
                lastRequestPayload = payload
            ))
            return NetworkResult.Success(Unit)
        }
        return NetworkResult.Error("Could not upload request to Google Drive")
    }

    suspend fun getTransfer(fileId: Long): CalibreTransferEntity? {
        return calibreTransferDao.getTransferById(fileId)
    }

    suspend fun removeTransfer(fileId: Long) {
        calibreTransferDao.deleteTransfer(fileId)
    }

    suspend fun markPackUploadFailed(fileId: Long, errorMessage: String) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        calibreTransferDao.updateTransfer(transfer.copy(
            status = CalibreTransferStatus.FAILED,
            errorMessage = errorMessage,
            lastUpdatedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun deleteFileFromPutio(token: String, fileId: Long): NetworkResult<Unit> {
        return withContext(Dispatchers.IO) {
            val ids = calibreTransferDao.getTransferById(fileId)?.parsedFileIds() ?: listOf(fileId)
            putioApiClient.deleteFiles(token, ids)
        }
    }

    suspend fun restartOrphanedUpload(transfer: CalibreTransferEntity) {
        val token = secureStorage.authTokenFlow.value
        val googleAccount = settingsRepository.googleTokenFlow.first()
        if (token.isBlank() || googleAccount.isBlank()) return

        val localUris: List<String> = transfer.localUrisJson?.let {
            try { json.decodeFromString(it) } catch (e: Exception) { null }
        } ?: transfer.sourceLocalUri?.let { listOf(it) } ?: return

        // Derive file names from batchData (PACK) or the single fileName
        val fileNames: List<String> = if (localUris.size == 1) {
            listOf(transfer.fileName)
        } else {
            try {
                val items = json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData ?: "")
                items.firstOrNull()?.files?.map { it.fileName } ?: return
            } catch (e: Exception) { return }
        }
        if (localUris.size != fileNames.size) return

        // Mark as restarting immediately so the polling loop doesn't launch a second restart
        updateUploadProgress(transfer.putioFileId, "Restarting…")

        // Find or create .putz_attachments
        val rootFiles = withContext(Dispatchers.IO) {
            (putioApiClient.listFiles(token, 0) as? NetworkResult.Success)?.data?.first ?: emptyList()
        }
        var folderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id
        if (folderId == null) {
            val result = withContext(Dispatchers.IO) { putioApiClient.createFolder(token, 0, ".putz_attachments") }
            folderId = (result as? NetworkResult.Success)?.data?.id
        }
        if (folderId == null) {
            updateUploadProgress(transfer.putioFileId, null)
            return
        }

        val uploadedFiles = mutableListOf<Triple<Long, String, String>>()
        val total = localUris.size

        for ((index, uriStr) in localUris.withIndex()) {
            val uri = android.net.Uri.parse(uriStr)
            val name = fileNames[index]

            // Dedup: skip upload if a file with the same name and size already exists
            val localSize = withContext(Dispatchers.IO) {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }
            if (localSize > 0) {
                val folderFiles = withContext(Dispatchers.IO) {
                    (putioApiClient.listFiles(token, folderId) as? NetworkResult.Success)?.data?.first ?: emptyList()
                }
                val existing = folderFiles.find { it.name == name && it.size == localSize }
                if (existing != null) {
                    val url = "${com.damarquez.putz.data.remote.PutioApiClient.BASE_URL}/files/${existing.id}/download?oauth_token=$token"
                    uploadedFiles.add(Triple(existing.id, url, name))
                    updateUploadProgress(transfer.putioFileId, "${index + 1}/$total · cached")
                    continue
                }
            }

            // Upload with timeout + exponential-backoff retry
            val retryableCodes = setOf(429, 500, 502, 503, 504)
            val maxAttempts = 5
            var delayMs = 5_000L
            var succeeded = false
            for (attempt in 1..maxAttempts) {
                val result = try {
                    withTimeout(3 * 60 * 1000L) {
                        putioApiClient.uploadFile(token, folderId, name, uri, context.contentResolver) { bytesWritten, totalBytes ->
                            val pct = if (totalBytes > 0) (bytesWritten * 100 / totalBytes).toInt() else 0
                            updateUploadProgress(transfer.putioFileId, "${index + 1}/$total · $pct%")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    NetworkResult.Error("Upload timed out", null)
                } catch (e: Exception) {
                    NetworkResult.Error(e.message ?: "Error", null)
                }

                val errorCode = (result as? NetworkResult.Error)?.code
                when {
                    result is NetworkResult.Success -> {
                        val url = "${com.damarquez.putz.data.remote.PutioApiClient.BASE_URL}/files/${result.data.id}/download?oauth_token=$token"
                        uploadedFiles.add(Triple(result.data.id, url, name))
                        succeeded = true
                        break
                    }
                    attempt < maxAttempts && (errorCode in retryableCodes || errorCode == null) -> {
                        delay(delayMs)
                        delayMs = minOf(delayMs * 2, 60_000L)
                    }
                    else -> break
                }
            }

            if (!succeeded) {
                updateUploadProgress(transfer.putioFileId, null)
                calibreTransferDao.updateTransfer(transfer.copy(
                    status = CalibreTransferStatus.FAILED,
                    errorMessage = "Upload failed on restart",
                    lastUpdatedAt = System.currentTimeMillis(),
                ))
                return
            }
        }

        updateUploadProgress(transfer.putioFileId, null)
        if (localUris.size == 1) {
            val (uploadedId, downloadUrl, _) = uploadedFiles.first()
            updateTransferAfterUpload(transfer.putioFileId, uploadedId, downloadUrl, googleAccount)
        } else {
            val audioFiles = uploadedFiles.map { (id, url, name) -> AudiobookFile(id, name, url) }
            updateAudiobookAfterUpload(transfer.putioFileId, audioFiles, googleAccount)
        }
    }
}
