package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.CalibreTransferDao
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.util.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
@Serializable
data class CalibreBatchItem(
    val type: String, // "SINGLE", "PACK", "ARCHIVE"
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
    val files: List<AudiobookFile>? = null, // For PACK
    val archiveMode: String? = null, // For ARCHIVE
)
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
)

@Serializable
data class AudiobookFile(
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
)

@Serializable
data class CalibreResponse(
    val action: String,
    val putio_file_id: Long? = null,
    val status: String,
    val error: String? = null,
    val daemon_status: String? = null, // "IDLE" or "WORKING"
)

@Singleton
class CalibreRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val calibreTransferDao: CalibreTransferDao,
    private val gDriveManager: GDriveManager,
    private val putioApiClient: PutioApiClient,
    private val secureStorage: SecureStorage,
    private val settingsRepository: com.damarquez.putz.settings.SettingsRepository,
) {
    private val _daemonStatus = MutableStateFlow<String?>(null)
    val daemonStatus = _daemonStatus.asStateFlow()

    private val json = Json { 
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    fun getTransfers(): Flow<List<CalibreTransferEntity>> = calibreTransferDao.getAllTransfers()

    suspend fun sendGlobalStatusProbe(googleAccount: String): Boolean {
        val request = mapOf("action" to "GLOBAL_STATUS_PROBE")
        val jsonStr = json.encodeToString(request)
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_global_status.json", jsonStr)
        return gDriveId != null
    }

    suspend fun addTransfer(
        putioFileId: Long,
        fileName: String,
        title: String,
        author: String,
        googleAccount: String,
        downloadUrl: String,
        archiveMode: String? = null,
        isTempUpload: Boolean = false,
        sourceLocalUri: String? = null,
        assembleBook: Boolean = false,
        calibreBookUuid: String? = null,
    ) {
        val initialItem = CalibreBatchItem(
            type = if (archiveMode != null) "ARCHIVE" else "SINGLE",
            putio_file_id = putioFileId,
            fileName = fileName,
            download_url = downloadUrl,
            archiveMode = archiveMode
        )
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = if (assembleBook) CalibreTransferStatus.ASSEMBLED else CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            isTempUpload = isTempUpload,
            sourceLocalUri = sourceLocalUri,
            batchData = json.encodeToString(listOf(initialItem)),
            calibreBookUuid = calibreBookUuid
        )
        calibreTransferDao.insertTransfer(transfer)

        if (assembleBook) return

        // Immediately try to upload request
        val request = CalibreBatchRequest(
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = listOf(initialItem),
            calibre_book_uuid = calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_$putioFileId.json", jsonStr)
        
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis()
            ))
        } else {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.FAILED,
                errorMessage = "Failed to upload to GDrive",
                lastUpdatedAt = System.currentTimeMillis()
            ))
        }
    }

    suspend fun addAudiobookPackTransfer(
        files: List<Pair<PutioFile, String>>,
        title: String,
        author: String,
        googleAccount: String,
        assembleBook: Boolean = false,
        customFileName: String? = null,
        calibreBookUuid: String? = null,
    ) {
        val primaryFileId = files.first().first.id
        val audioFiles = files.map { (file, url) ->
            AudiobookFile(file.id, file.name, url)
        }
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
            status = if (assembleBook) CalibreTransferStatus.ASSEMBLED else CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = files.joinToString(",") { (file, _) -> file.id.toString() },
            batchData = json.encodeToString(listOf(initialItem)),
            calibreBookUuid = calibreBookUuid
        )
        calibreTransferDao.insertTransfer(transfer)

        if (assembleBook) return

        val request = CalibreBatchRequest(
            putio_file_id = primaryFileId,
            title = title,
            author = author,
            items = listOf(initialItem),
            calibre_book_uuid = calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_$primaryFileId.json", jsonStr)

        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
            ))
        } else {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.FAILED,
                errorMessage = "Failed to upload to GDrive",
                lastUpdatedAt = System.currentTimeMillis(),
            ))
        }
    }

    suspend fun appendToAssembly(
        assemblyFileId: Long,
        title: String,
        author: String,
        newItem: CalibreBatchItem,
        newFileIds: List<Long>,
    ) {
        val transfer = calibreTransferDao.getTransferById(assemblyFileId) ?: return
        val currentItems = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: emptyList()
        
        val updatedItems = currentItems + newItem
        val updatedIds = (transfer.parsedFileIds() + newFileIds).distinct()
        
        calibreTransferDao.updateTransfer(transfer.copy(
            title = title,
            author = author,
            batchData = json.encodeToString(updatedItems),
            allPutioFileIds = updatedIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun getPendingAssemblies(): List<CalibreTransferEntity> {
        return calibreTransferDao.getAllTransfers().first().filter { 
            it.status == CalibreTransferStatus.ASSEMBLED 
        }
    }

    suspend fun syncMetadataDb(googleAccount: String, destination: File): Boolean {
        val success = gDriveManager.downloadMetadataDb(googleAccount, destination)
        if (success) {
            // Update the baseline timestamp after a manual or automatic sync
            gDriveManager.getFileMetadata(googleAccount, "assets.db")?.let { metadata ->
                val timestamp = metadata.second
                settingsRepository.saveLastSyncTimestamp(timestamp)
                settingsRepository.saveLibraryHasUpdates(false)
            }
        }
        return success
    }

    suspend fun pollLibraryUpdates(googleAccount: String) {
        val metadata = gDriveManager.getFileMetadata(googleAccount, "assets.db") ?: return
        val remoteTimestamp = metadata.second
        val localTimestamp = settingsRepository.lastSyncTimestampFlow.first()
        
        if (localTimestamp == 0L) {
            // First time: set baseline
            settingsRepository.saveLastSyncTimestamp(remoteTimestamp)
        } else if (remoteTimestamp > localTimestamp) {
            settingsRepository.saveLibraryHasUpdates(true)
        }
    }

    suspend fun pollHeartbeat(googleAccount: String) {
        // Find heartbeat.json in .calibre_integration
        val service = gDriveManager.getDriveService(googleAccount)
        val libFolderId = gDriveManager.getLibraryFolderId(service) ?: return
        val rootId = gDriveManager.findFolder(service, ".calibre_integration", libFolderId) ?: return
        
        val result = service.files().list()
            .setQ("name = 'heartbeat.json' and '$rootId' in parents and trashed = false")
            .setFields("files(id)")
            .execute()
        
        val fileId = result.files?.firstOrNull()?.id ?: return
        val content = gDriveManager.downloadFileContent(googleAccount, fileId) ?: return
        
        try {
            val heartbeat = json.decodeFromString<Heartbeat>(content)
            val status = heartbeat.status.uppercase()
            _daemonStatus.value = status
            settingsRepository.saveDaemonStatus(status)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Serializable
    private data class Heartbeat(
        val status: String,
        val last_active: Long
    )

    suspend fun pollResponses(googleAccount: String) {
        val responses = gDriveManager.listResponses(googleAccount)
        responses.forEach { file ->
            val content = gDriveManager.downloadFileContent(googleAccount, file.id)
            if (content != null) {
                try {
                    val response = json.decodeFromString<CalibreResponse>(content)
                    
                    if (response.action == "GLOBAL_STATUS_PROBE") {
                        _daemonStatus.value = response.daemon_status
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
                                    lastUpdatedAt = System.currentTimeMillis()
                                ))

                                // Phase 3: Cleanup temporary uploads
                                if (newStatus == CalibreTransferStatus.COMPLETED && transfer.isTempUpload) {
                                    val token = secureStorage.authTokenFlow.value
                                    if (token.isNotBlank()) {
                                        deleteFileFromPutio(token, transfer.putioFileId)
                                    }
                                }

                                // Auto-retry logic: if a response says the book wasn't found (daemon missed it), 
                                // automatically trigger a retry. Staggered to avoid simultaneous GDrive uploads.
                                if (newStatus == CalibreTransferStatus.FAILED && response.error?.contains("not found", ignoreCase = true) == true) {
                                    if (transfer.retryCount < 3) {
                                        val token = secureStorage.authTokenFlow.value
                                        if (token.isNotBlank()) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val delayMs = Random.nextLong(2000, 60000)
                                                delay(delayMs)
                                                retryTransfer(transfer.putioFileId, googleAccount, token)
                                            }
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
                    // Delete response file once processed
                    gDriveManager.deleteFile(googleAccount, file.id)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun checkExistsByUuid(dbFile: File, uuid: String): Triple<Long, String, String>? = withContext(Dispatchers.IO) {
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
                        return@withContext Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_cover_$putioFileId.json", jsonStr)

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
            calibreBookUuid = calibreBookUuid
        )
        calibreTransferDao.insertTransfer(transfer)
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

        val request = CalibreBatchRequest(
            putio_file_id = transfer.putioFileId,
            title = transfer.title,
            author = transfer.author,
            items = items,
            is_probe = true
        )

        val jsonStr = json.encodeToString(request)
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_probe_${transfer.putioFileId}.json", jsonStr)
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                lastUpdatedAt = System.currentTimeMillis()
            ))
            return true
        }
        return false
    }

    suspend fun retryTransfer(fileId: Long, googleAccount: String, putioToken: String): Boolean {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return false
        
        val items = if (!transfer.batchData.isNullOrBlank()) {
            json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData).map { item ->
                // Refresh download URLs for each item
                when (item.type) {
                    "SINGLE", "ARCHIVE" -> {
                        item.copy(download_url = "${PutioApiClient.BASE_URL}/files/${item.putio_file_id}/download?oauth_token=$putioToken")
                    }
                    "PACK" -> {
                        val updatedFiles = item.files?.map { file ->
                            file.copy(download_url = "${PutioApiClient.BASE_URL}/files/${file.putio_file_id}/download?oauth_token=$putioToken")
                        }
                        item.copy(files = updatedFiles)
                    }
                    else -> item
                }
            }
        } else {
            // Fallback for old records without batchData
            val ids = transfer.parsedFileIds()
            if (ids.size > 1) {
                val audioFiles = ids.map { id ->
                    AudiobookFile(id, "RETRY", "${PutioApiClient.BASE_URL}/files/$id/download?oauth_token=$putioToken")
                }
                listOf(CalibreBatchItem("PACK", transfer.putioFileId, transfer.fileName, files = audioFiles))
            } else {
                listOf(CalibreBatchItem(
                    if (MetadataUtils.isArchive(transfer.fileName)) "ARCHIVE" else "SINGLE",
                    transfer.putioFileId,
                    transfer.fileName,
                    "${PutioApiClient.BASE_URL}/files/${transfer.putioFileId}/download?oauth_token=$putioToken"
                ))
            }
        }

        val request = CalibreBatchRequest(
            putio_file_id = transfer.putioFileId,
            title = transfer.title,
            author = transfer.author,
            items = items,
            calibre_book_uuid = transfer.calibreBookUuid
        )
        val jsonStr = json.encodeToString(request)

        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_${transfer.putioFileId}.json", jsonStr)
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                retryCount = transfer.retryCount + 1,
                errorMessage = null
            ))
            return true
        }
        return false
    }

    suspend fun getTransfer(fileId: Long): CalibreTransferEntity? {
        return calibreTransferDao.getTransferById(fileId)
    }

    suspend fun removeTransfer(fileId: Long) {
        calibreTransferDao.deleteTransfer(fileId)
    }

    suspend fun deleteFileFromPutio(token: String, fileId: Long): NetworkResult<Unit> {
        return withContext(Dispatchers.IO) {
            val ids = calibreTransferDao.getTransferById(fileId)?.parsedFileIds() ?: listOf(fileId)
            putioApiClient.deleteFiles(token, ids)
        }
    }
}
