package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.AccountInfo
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.PutioApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilesRepository @Inject constructor(
    private val apiClient: PutioApiClient,
) {
    suspend fun listFiles(
        token: String,
        parentId: Long = 0L,
    ): NetworkResult<Pair<List<PutioFile>, PutioFile?>> =
        withContext(Dispatchers.IO) {
            apiClient.listFiles(token, parentId)
        }

    suspend fun getAccountInfo(token: String): NetworkResult<AccountInfo> =
        withContext(Dispatchers.IO) {
            apiClient.getAccountInfo(token)
        }

    fun getDownloadUrl(token: String, fileId: Long): String {
        return "${PutioApiClient.BASE_URL}/files/$fileId/download?oauth_token=$token"
    }
}
