package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.remote.PutioApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransfersRepository @Inject constructor(
    private val apiClient: PutioApiClient,
) {
    suspend fun listTransfers(token: String): NetworkResult<List<PutioTransfer>> =
        withContext(Dispatchers.IO) {
            apiClient.listTransfers(token)
        }
}
