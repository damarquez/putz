package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.FolderDisplayNameDao
import com.damarquez.putz.data.local.FolderDisplayNameEntity
import com.damarquez.putz.data.model.AccountInfo
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.PutioApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilesRepository @Inject constructor(
    private val apiClient: PutioApiClient,
    private val folderDisplayNameDao: FolderDisplayNameDao,
) {
    private val fileCache = mutableMapOf<Long, Pair<List<PutioFile>, PutioFile?>>()

    fun getCached(parentId: Long): Pair<List<PutioFile>, PutioFile?>? = fileCache[parentId]

    // Applies locally-stored "change display name" overrides to folders — a purely cosmetic
    // Putz-side layer, see PutioFile.customDisplayName. Bulk-loads the (small) override table
    // once per call instead of one query per folder.
    private suspend fun decorate(files: List<PutioFile>): List<PutioFile> {
        if (files.none { it.isFolder }) return files
        val overrides = folderDisplayNameDao.getAll().associate { it.putioFileId to it.displayName }
        if (overrides.isEmpty()) return files
        return files.map { f ->
            if (f.isFolder) overrides[f.id]?.let { f.copy(customDisplayName = it) } ?: f else f
        }
    }

    private suspend fun decorate(file: PutioFile): PutioFile {
        if (!file.isFolder) return file
        val override = folderDisplayNameDao.getDisplayName(file.id) ?: return file
        return file.copy(customDisplayName = override)
    }

    suspend fun listFiles(
        token: String,
        parentId: Long = 0L,
    ): NetworkResult<Pair<List<PutioFile>, PutioFile?>> =
        withContext(Dispatchers.IO) {
            when (val result = apiClient.listFiles(token, parentId)) {
                is NetworkResult.Success -> {
                    val decorated = decorate(result.data.first) to result.data.second?.let { decorate(it) }
                    fileCache[parentId] = decorated
                    NetworkResult.Success(decorated)
                }
                else -> result
            }
        }

    suspend fun searchFiles(
        token: String,
        query: String,
        parentId: Long = 0L,
    ): NetworkResult<List<PutioFile>> =
        withContext(Dispatchers.IO) {
            when (val result = apiClient.searchFiles(token, query, parentId)) {
                is NetworkResult.Success -> NetworkResult.Success(decorate(result.data))
                else -> result
            }
        }

    suspend fun getAccountInfo(token: String): NetworkResult<AccountInfo> =
        withContext(Dispatchers.IO) {
            apiClient.getAccountInfo(token)
        }

    suspend fun getFile(token: String, fileId: Long): NetworkResult<PutioFile> =
        withContext(Dispatchers.IO) {
            when (val result = apiClient.getFile(token, fileId)) {
                is NetworkResult.Success -> NetworkResult.Success(decorate(result.data))
                else -> result
            }
        }

    // Sets, or (when displayName is null/blank) clears, a folder's local display-name override.
    suspend fun setFolderDisplayName(fileId: Long, displayName: String?) =
        withContext(Dispatchers.IO) {
            if (displayName.isNullOrBlank()) {
                folderDisplayNameDao.delete(fileId)
            } else {
                folderDisplayNameDao.upsert(FolderDisplayNameEntity(fileId, displayName))
            }
            fileCache.clear()
        }

    fun getDownloadUrl(token: String, fileId: Long): String {
        return "${PutioApiClient.BASE_URL}/files/$fileId/download?oauth_token=$token"
    }

    suspend fun resolveDirectDownloadUrl(url: String): String =
        withContext(Dispatchers.IO) {
            try {
                val noFollowClient = apiClient.okHttpClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                val request = okhttp3.Request.Builder().url(url).build()
                noFollowClient.newCall(request).execute().use { response ->
                    response.header("Location") ?: url
                }
            } catch (e: Exception) {
                url
            }
        }

    suspend fun deleteFiles(token: String, fileIds: List<Long>): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            apiClient.deleteFiles(token, fileIds)
        }

    suspend fun createFolder(token: String, parentId: Long, name: String): NetworkResult<PutioFile> =
        withContext(Dispatchers.IO) {
            apiClient.createFolder(token, parentId, name)
        }

    suspend fun renameFile(token: String, fileId: Long, newName: String): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            apiClient.renameFile(token, fileId, newName)
        }

    suspend fun uploadFile(
        token: String,
        parentId: Long,
        name: String,
        uri: android.net.Uri,
        contentResolver: android.content.ContentResolver,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): NetworkResult<PutioFile> =
        withContext(Dispatchers.IO) {
            apiClient.uploadFile(token, parentId, name, uri, contentResolver, onProgress)
        }

    suspend fun uploadFileFromStream(
        token: String,
        parentId: Long,
        name: String,
        inputStream: java.io.InputStream,
        fileSize: Long,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): NetworkResult<PutioFile> =
        withContext(Dispatchers.IO) {
            apiClient.uploadFileFromStream(token, parentId, name, inputStream, fileSize, onProgress)
        }

    suspend fun downloadToFile(url: String, targetFile: File): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder().url(url).build()
                apiClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext NetworkResult.Error("HTTP ${response.code}")
                    val body = response.body ?: return@withContext NetworkResult.Error("Empty body")
                    
                    targetFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    NetworkResult.Success(Unit)
                }
            } catch (e: Exception) {
                NetworkResult.Error(e.message ?: "Download failed")
            }
        }
}
