package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TransferHistoryJson
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferHistoryRepository @Inject constructor(
    private val putioApiClient: PutioApiClient,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Survives back-navigation because this class is a singleton.
    // Returned when a fresh fetch fails so users never see a spurious "not available" error
    // during the brief window when the daemon is rotating the history file on put.io.
    private var cache: TransferHistoryJson? = null

    // CONTRACT: REGISTER_TRANSFER_HISTORY — fetch the history JSON from put.io by stored file ID
    suspend fun fetchHistory(token: String): TransferHistoryJson? {
        val fileId = settingsRepository.historyFileIdFlow.first() ?: return cache
        return withContext(Dispatchers.IO) {
            val result = putioApiClient.downloadFileAsString(token, fileId)
            val fresh = (result as? NetworkResult.Success)?.data?.let { body ->
                try { json.decodeFromString<TransferHistoryJson>(body) } catch (_: Exception) { null }
            }
            if (fresh != null) cache = fresh
            fresh ?: cache
        }
    }
}
