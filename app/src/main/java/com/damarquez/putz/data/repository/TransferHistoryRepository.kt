package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileSearchResult
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TransferHistoryJson
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.data.transport.DaemonTransport
import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferHistoryRepository @Inject constructor(
    private val putioApiClient: PutioApiClient,
    private val settingsRepository: SettingsRepository,
    private val daemonTransport: DaemonTransport,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // CONTRACT: REGISTER_TRANSFER_HISTORY — fetch the history JSON from put.io by stored file ID.
    // Returns fresh data on success, persisted cache on any failure, null if neither is available.
    suspend fun fetchHistory(token: String): TransferHistoryJson? {
        val fileId = settingsRepository.historyFileIdFlow.first() ?: return getCachedHistory()
        return withContext(Dispatchers.IO) {
            val result = putioApiClient.downloadFileAsString(token, fileId)
            val fresh = (result as? NetworkResult.Success)?.data?.let { body ->
                try { json.decodeFromString<TransferHistoryJson>(body) } catch (_: Exception) { null }
            }
            if (fresh != null) {
                settingsRepository.saveHistoryJsonCache(json.encodeToString(fresh))
                fresh
            } else {
                getCachedHistory()
            }
        }
    }

    suspend fun getCachedHistory(): TransferHistoryJson? =
        settingsRepository.historyJsonCacheFlow.first()?.let { raw ->
            try { json.decodeFromString<TransferHistoryJson>(raw) } catch (_: Exception) { null }
        }

    // CONTRACT: FETCH_HISTORY_FILES — per-file listing is never in the routine history JSON;
    // fetched on demand for the detail view.
    suspend fun fetchEntryFiles(infoHash: String): List<HistoryEntryFile>? {
        val googleAccount = settingsRepository.googleTokenFlow.first()
        if (googleAccount.isBlank()) return null
        val appId = settingsRepository.getOrCreateAppId()
        return daemonTransport.getHistoryEntryFiles(googleAccount, appId, infoHash)
    }

    // CONTRACT: SEARCH_HISTORY_FILES — server-side file-name search backing "search in files".
    suspend fun searchFiles(query: String): List<HistoryFileSearchResult>? {
        val googleAccount = settingsRepository.googleTokenFlow.first()
        if (googleAccount.isBlank()) return null
        val appId = settingsRepository.getOrCreateAppId()
        return daemonTransport.searchHistoryFiles(googleAccount, appId, query)
    }

    // CONTRACT: FORGET_TRANSFER_HISTORY — permanently removes an entry so its info_hash can be
    // re-added later without tripping the "Already in history" duplicate check.
    suspend fun forgetEntry(infoHash: String): Boolean {
        val googleAccount = settingsRepository.googleTokenFlow.first()
        if (googleAccount.isBlank()) return false
        val appId = settingsRepository.getOrCreateAppId()
        val success = daemonTransport.forgetHistoryEntry(googleAccount, appId, infoHash)
        if (success) {
            // Patch the local cache immediately rather than waiting for the daemon's re-upload
            // + next heartbeat to propagate the new transfer_history.json — TransfersRepository's
            // isCompletedInHistory() reads this cache directly, so without this the "already
            // completed" duplicate block can keep firing on a re-add for a while after the entry
            // is actually gone.
            getCachedHistory()?.let { cached ->
                val patched = cached.copy(entries = cached.entries.filterNot { it.infoHash.equals(infoHash, ignoreCase = true) })
                settingsRepository.saveHistoryJsonCache(json.encodeToString(patched))
            }
        }
        return success
    }
}
