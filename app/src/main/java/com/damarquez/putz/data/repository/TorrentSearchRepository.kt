package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.MediaType
import com.damarquez.putz.data.model.MetaCandidate
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TorrentSearchResult
import com.damarquez.putz.data.remote.TorrentSearchClient
import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentSearchRepository @Inject constructor(
    private val client: TorrentSearchClient,
    private val settingsRepository: SettingsRepository,
) {

    val jackettConfiguredFlow: Flow<Boolean> = combine(
        settingsRepository.jackettBaseUrlFlow,
        settingsRepository.jackettApiKeyFlow,
    ) { url, key -> url.isNotBlank() && key.isNotBlank() }

    suspend fun searchMetadata(type: MediaType, query: String): NetworkResult<List<MetaCandidate>> =
        withContext(Dispatchers.IO) { client.searchMetadata(type, query) }

    suspend fun getStreams(
        candidate: MetaCandidate,
        season: Int? = null,
        episode: Int? = null,
    ): NetworkResult<List<TorrentSearchResult>> = withContext(Dispatchers.IO) {
        val videoId = when (candidate.type) {
            MediaType.MOVIE -> candidate.imdbId
            MediaType.SERIES -> "${candidate.imdbId}:${season ?: 1}:${episode ?: 1}"
        }
        client.getTorrentioStreams(candidate.type, videoId)
    }

    suspend fun searchJackett(query: String): NetworkResult<List<TorrentSearchResult>> =
        withContext(Dispatchers.IO) {
            val baseUrl = settingsRepository.jackettBaseUrlFlow.first()
            val apiKey = settingsRepository.jackettApiKeyFlow.first()
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext NetworkResult.Error("Jackett is not configured. Set its URL and API key in Settings.")
            }
            client.searchJackett(baseUrl, apiKey, query)
        }
}
