package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TrashFile
import com.damarquez.putz.data.remote.PutioApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashRepository @Inject constructor(
    private val apiClient: PutioApiClient,
) {
    suspend fun listTrash(token: String): NetworkResult<Pair<List<TrashFile>, Long>> =
        withContext(Dispatchers.IO) { apiClient.listTrash(token) }

    suspend fun restoreTrash(token: String, fileIds: List<Long>): NetworkResult<Unit> =
        withContext(Dispatchers.IO) { apiClient.restoreTrash(token, fileIds) }

    suspend fun deleteFromTrash(token: String, fileIds: List<Long>): NetworkResult<Unit> =
        withContext(Dispatchers.IO) { apiClient.deleteFromTrash(token, fileIds) }

    suspend fun emptyTrash(token: String): NetworkResult<Unit> =
        withContext(Dispatchers.IO) { apiClient.emptyTrash(token) }
}
