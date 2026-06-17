package com.damarquez.putz.data.remote

import android.net.Uri
import com.damarquez.putz.data.model.MediaType
import com.damarquez.putz.data.model.MetaCandidate
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TorrentSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class CinemetaMeta(
    val id: String = "",
    val name: String = "",
    val releaseInfo: String? = null,
    val poster: String? = null,
)

@Serializable
private data class CinemetaCatalogResponse(
    val metas: List<CinemetaMeta> = emptyList(),
)

@Serializable
private data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
)

@Serializable
private data class TorrentioStreamsResponse(
    val streams: List<TorrentioStream> = emptyList(),
)

@Serializable
private data class JackettItem(
    @SerialName("Title") val title: String = "",
    @SerialName("Tracker") val tracker: String = "",
    @SerialName("Size") val size: Long = 0L,
    @SerialName("Seeders") val seeders: Int? = null,
    @SerialName("MagnetUri") val magnetUri: String? = null,
    @SerialName("Link") val link: String? = null,
    @SerialName("InfoHash") val infoHash: String? = null,
)

@Serializable
private data class JackettResponse(
    @SerialName("Results") val results: List<JackettItem> = emptyList(),
)

/**
 * Torrent search engines:
 *  - Cinemeta (Stremio metadata catalog) resolves free-text movie/series queries to IMDb IDs.
 *  - Torrentio (Stremio aggregator addon) returns torrents for an exact IMDb ID.
 *  - Jackett (self-hosted proxy) handles general content with a free-text query.
 */
@Singleton
class TorrentSearchClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        const val TORRENTIO_BASE_URL = "https://torrentio.strem.io"
        // Jackett searches across many indexers can take 160+ seconds (each indexer runs
        // in parallel but the slowest one gates the response). Use a generous timeout.
        private const val JACKETT_TIMEOUT_SECONDS = 210L

        private val SEEDERS_REGEX = Regex("""👤\s*(\d+)""")
        private val SIZE_REGEX = Regex("""💾\s*([\d.]+)\s*(TB|GB|MB|KB|B)""", RegexOption.IGNORE_CASE)
        private val PROVIDER_REGEX = Regex("""⚙️\s*(\S[^\n]*)""")
    }

    private val jackettOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(JACKETT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(JACKETT_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
            .build()
    }

    fun searchMetadata(type: MediaType, query: String): NetworkResult<List<MetaCandidate>> {
        return try {
            val url = "$CINEMETA_BASE_URL/catalog/${type.stremioType}/top/search=${Uri.encode(query)}.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    return NetworkResult.Error("Metadata lookup failed: HTTP ${response.code}", response.code)
                }
                val parsed = json.decodeFromString<CinemetaCatalogResponse>(body)
                val candidates = parsed.metas
                    .filter { it.id.startsWith("tt") && it.name.isNotBlank() }
                    .map {
                        MetaCandidate(
                            imdbId = it.id,
                            name = it.name,
                            releaseInfo = it.releaseInfo?.takeIf { info -> info.isNotBlank() },
                            poster = it.poster,
                            type = type,
                        )
                    }
                NetworkResult.Success(candidates)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    /** [videoId] is a bare IMDb ID for movies, or "ttXXXX:season:episode" for series. */
    fun getTorrentioStreams(type: MediaType, videoId: String): NetworkResult<List<TorrentSearchResult>> {
        return try {
            val url = "$TORRENTIO_BASE_URL/stream/${type.stremioType}/$videoId.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    return NetworkResult.Error("Torrentio lookup failed: HTTP ${response.code}", response.code)
                }
                val parsed = json.decodeFromString<TorrentioStreamsResponse>(body)
                val results = parsed.streams.mapNotNull { stream ->
                    val infoHash = stream.infoHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val rawTitle = stream.title ?: ""
                    val displayTitle = rawTitle.lineSequence().firstOrNull()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: infoHash
                    TorrentSearchResult(
                        title = displayTitle,
                        sizeBytes = parseSize(rawTitle),
                        seeders = SEEDERS_REGEX.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull(),
                        magnet = "magnet:?xt=urn:btih:$infoHash&dn=${Uri.encode(displayTitle)}",
                        infoHash = infoHash.lowercase(),
                        source = PROVIDER_REGEX.find(rawTitle)?.groupValues?.get(1)?.trim()
                            ?.let { "$it (Torrentio)" } ?: "Torrentio",
                    )
                }.sortedByDescending { it.seeders ?: 0 }
                NetworkResult.Success(results)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    fun searchJackett(baseUrl: String, apiKey: String, query: String): NetworkResult<List<TorrentSearchResult>> {
        return try {
            val url = "${baseUrl.trimEnd('/')}/api/v2.0/indexers/all/results".toHttpUrl().newBuilder()
                .addQueryParameter("apikey", apiKey)
                .addQueryParameter("Query", query)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            jackettOkHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return NetworkResult.Error("Empty response", response.code)
                if (!response.isSuccessful) {
                    return NetworkResult.Error("Jackett search failed: HTTP ${response.code}", response.code)
                }
                val parsed = json.decodeFromString<JackettResponse>(body)
                val results = parsed.results.mapNotNull { item ->
                    // put.io's cloud cannot reach .torrent links served by the LAN Jackett
                    // instance, so only magnet URIs (direct or built from the info hash) work.
                    val magnet = item.magnetUri?.takeIf { it.startsWith("magnet:") }
                        ?: item.link?.takeIf { it.startsWith("magnet:") }
                        ?: item.infoHash?.takeIf { it.isNotBlank() }
                            ?.let { "magnet:?xt=urn:btih:$it&dn=${Uri.encode(item.title)}" }
                        ?: return@mapNotNull null
                    TorrentSearchResult(
                        title = item.title,
                        sizeBytes = item.size.takeIf { it > 0 },
                        seeders = item.seeders,
                        magnet = magnet,
                        infoHash = item.infoHash?.lowercase(),
                        source = "${item.tracker} (Jackett)",
                    )
                }.sortedByDescending { it.seeders ?: 0 }
                NetworkResult.Success(results)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseSize(torrentioTitle: String): Long? {
        val match = SIZE_REGEX.find(torrentioTitle) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "TB" -> 1024L * 1024 * 1024 * 1024
            "GB" -> 1024L * 1024 * 1024
            "MB" -> 1024L * 1024
            "KB" -> 1024L
            else -> 1L
        }
        return (value * multiplier).toLong()
    }
}
