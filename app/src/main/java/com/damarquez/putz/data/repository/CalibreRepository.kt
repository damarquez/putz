package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.CalibreTransferDao
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.security.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CalibreRequest(
    val action: String,
    val putio_file_id: Long,
    val title: String,
    val author: String,
    val fileName: String,
    val download_url: String? = null,
    val archiveMode: String? = null,
    val is_probe: Boolean? = null,
)

@Serializable
data class AudiobookFile(
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
)

@Serializable
data class AudiobookPackRequest(
    val action: String,
    val putio_file_id: Long,
    val title: String,
    val author: String,
    val files: List<AudiobookFile>,
    val is_probe: Boolean? = null,
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
) {
    private val _daemonStatus = MutableStateFlow<String?>(null)
    val daemonStatus = _daemonStatus.asStateFlow()

    private val json = Json { 
        explicitNulls = false
        ignoreUnknownKeys = true
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
    ) {
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            isTempUpload = isTempUpload,
        )
        calibreTransferDao.insertTransfer(transfer)

        // Immediately try to upload request
        val request = CalibreRequest("ADD_BOOK", putioFileId, title, author, fileName, downloadUrl, archiveMode)
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
    ) {
        val primaryFileId = files.first().first.id
        val transfer = CalibreTransferEntity(
            putioFileId = primaryFileId,
            fileName = "${files.size} MP3 files",
            title = title,
            author = author,
            status = CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = files.joinToString(",") { (file, _) -> file.id.toString() },
        )
        calibreTransferDao.insertTransfer(transfer)

        val audioFiles = files.map { (file, url) ->
            AudiobookFile(file.id, file.name, url)
        }
        val request = AudiobookPackRequest(
            action = "ADD_AUDIOBOOK_PACK",
            putio_file_id = primaryFileId,
            title = title,
            author = author,
            files = audioFiles,
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

    suspend fun syncMetadataDb(googleAccount: String, destination: File): Boolean {
        return gDriveManager.downloadMetadataDb(googleAccount, destination)
    }

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
                            
                            // Always update lastUpdatedAt if we got a valid response for this transfer
                            // This prevents probes from triggering while the daemon is actively sending updates
                            if (newStatus.ordinal > transfer.status.ordinal) {
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

    suspend fun checkExists(dbFile: File, title: String, author: String): Boolean = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext false
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val query = """
                    SELECT count(*) FROM books 
                    JOIN books_authors_link ON books.id = books_authors_link.book 
                    JOIN authors ON authors.id = books_authors_link.author 
                    WHERE books.title LIKE ? AND authors.name LIKE ?
                """.trimIndent()
                db.rawQuery(query, arrayOf("%$title%", "%$author%")).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getInt(0) > 0
                    } else false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
        
        val isPack = transfer.allPutioFileIds.contains(",")
        val jsonStr = if (isPack) {
            val audioFiles = transfer.parsedFileIds().map { id ->
                AudiobookFile(id, "PROBE", null)
            }
            val request = AudiobookPackRequest(
                action = "ADD_AUDIOBOOK_PACK",
                putio_file_id = transfer.putioFileId,
                title = transfer.title,
                author = transfer.author,
                files = audioFiles,
                is_probe = true
            )
            json.encodeToString(request)
        } else {
            val request = CalibreRequest(
                action = "ADD_BOOK",
                putio_file_id = transfer.putioFileId,
                title = transfer.title,
                author = transfer.author,
                fileName = transfer.fileName,
                download_url = null,
                is_probe = true
            )
            json.encodeToString(request)
        }

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
        
        // Re-fetch download URLs for all files
        val ids = transfer.parsedFileIds()
        val filesWithUrls = ids.map { id ->
            id to "${PutioApiClient.BASE_URL}/files/$id/download?oauth_token=$putioToken"
        }
        
        val isPack = transfer.allPutioFileIds.contains(",")
        val jsonStr = if (isPack) {
            val audioFiles = filesWithUrls.map { (id, url) ->
                AudiobookFile(id, "RETRY", url) // We don't store original filename in the list, but daemon uses download_url
            }
            val request = AudiobookPackRequest(
                action = "ADD_AUDIOBOOK_PACK",
                putio_file_id = transfer.putioFileId,
                title = transfer.title,
                author = transfer.author,
                files = audioFiles,
            )
            json.encodeToString(request)
        } else {
            val request = CalibreRequest(
                action = "ADD_BOOK",
                putio_file_id = transfer.putioFileId,
                title = transfer.title,
                author = transfer.author,
                fileName = transfer.fileName,
                download_url = filesWithUrls.first().second,
            )
            json.encodeToString(request)
        }

        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_retry_${transfer.putioFileId}.json", jsonStr)
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
