package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.CalibreTransferDao
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.data.remote.PutioApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    val download_url: String,
)

@Serializable
data class AudiobookFile(
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String,
)

@Serializable
data class AudiobookPackRequest(
    val action: String,
    val putio_file_id: Long,
    val title: String,
    val author: String,
    val files: List<AudiobookFile>,
)

@Serializable
data class CalibreResponse(
    val action: String,
    val putio_file_id: Long,
    val status: String,
    val error: String? = null,
)

@Singleton
class CalibreRepository @Inject constructor(
    private val calibreTransferDao: CalibreTransferDao,
    private val gDriveManager: GDriveManager,
    private val putioApiClient: PutioApiClient,
) {
    fun getTransfers(): Flow<List<CalibreTransferEntity>> = calibreTransferDao.getAllTransfers()

    suspend fun addTransfer(
        putioFileId: Long,
        fileName: String,
        title: String,
        author: String,
        googleAccount: String,
        downloadUrl: String,
    ) {
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )
        calibreTransferDao.insertTransfer(transfer)

        // Immediately try to upload request
        val request = CalibreRequest("ADD_BOOK", putioFileId, title, author, fileName, downloadUrl)
        val json = Json.encodeToString(request)
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_$putioFileId.json", json)
        
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
        val json = Json.encodeToString(request)
        val gDriveId = gDriveManager.uploadRequest(googleAccount, "req_$primaryFileId.json", json)

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
                    val response = Json.decodeFromString<CalibreResponse>(content)
                    val transfer = calibreTransferDao.getTransferById(response.putio_file_id)
                    if (transfer != null) {
                        val newStatus = when (response.status) {
                            "PROCESSING" -> CalibreTransferStatus.PROCESSING
                            "COMPLETED" -> CalibreTransferStatus.COMPLETED
                            "FAILED" -> CalibreTransferStatus.FAILED
                            else -> transfer.status
                        }
                        if (newStatus.ordinal > transfer.status.ordinal) {
                            calibreTransferDao.updateTransfer(transfer.copy(
                                status = newStatus,
                                errorMessage = response.error,
                                lastUpdatedAt = System.currentTimeMillis()
                            ))
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

    suspend fun getTransfer(fileId: Long): CalibreTransferEntity? {
        return calibreTransferDao.getTransferById(fileId)
    }

    suspend fun removeTransfer(fileId: Long) {
        calibreTransferDao.deleteTransfer(fileId)
    }

    suspend fun deleteFileFromPutio(token: String, fileId: Long): NetworkResult<Unit> {
        return withContext(Dispatchers.IO) {
            putioApiClient.deleteFiles(token, listOf(fileId))
        }
    }
}
