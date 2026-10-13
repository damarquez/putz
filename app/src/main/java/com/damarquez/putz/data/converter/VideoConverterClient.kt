package com.damarquez.putz.data.converter

import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// CONTRACT: video converter API (CONTRACTS.md §48, sidekick calibre_assets/video_converter_api.py).
// The converter is its own container on the daemon's PC, on port 9092 of the same host as the
// daemon's LAN API, with the same X-Sidekick-Key.
const val VIDEO_CONVERTER_PORT = 9092

@Serializable
data class ConverterCurrent(
    @SerialName("item_id") val itemId: Long? = null,
    val title: String? = null,
    val section: String? = null,
    val phase: String? = null,
    val progress: Double? = null,
    val speed: Double? = null,
    @SerialName("phase_eta_s") val phaseEtaS: Long? = null,
    @SerialName("started_at") val startedAt: Double? = null,
)

@Serializable
data class ConverterSession(
    @SerialName("rating_key") val ratingKey: String? = null,
    val title: String? = null,
    val user: String? = null,
    val state: String? = null,
)

@Serializable
data class ConverterSection(
    @SerialName("section_id") val sectionId: Int? = null,
    @SerialName("section_title") val sectionTitle: String? = null,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class ConverterTotals(
    @SerialName("bytes_before") val bytesBefore: Long = 0,
    @SerialName("bytes_after") val bytesAfter: Long = 0,
    val seconds: Double = 0.0,
    val done: Int = 0,
)

@Serializable
data class ConverterStatus(
    val state: String = "unknown",
    val paused: Boolean = false,
    val current: ConverterCurrent? = null,
    val sessions: List<ConverterSession> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val sections: List<ConverterSection> = emptyList(),
    val totals: ConverterTotals = ConverterTotals(),
    val encoder: String? = null,
    @SerialName("originals_mode") val originalsMode: String? = null,
    @SerialName("last_inventory_at") val lastInventoryAt: Double? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class ConverterItem(
    val id: Long,
    val path: String = "",
    @SerialName("rating_key") val ratingKey: String? = null,
    @SerialName("section_title") val sectionTitle: String? = null,
    val title: String? = null,
    val size: Long? = null,
    val state: String = "",
    val source: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    @SerialName("finished_at") val finishedAt: Double? = null,
    val front: Boolean = false,
    val reasons: List<String> = emptyList(),
    val result: JsonObject? = null,
)

@Serializable
private data class ItemsPage(val items: List<ConverterItem> = emptyList(), val total: Int? = null)

@Serializable
data class ConverterSearchFile(
    val path: String = "",
    @SerialName("needs_work") val needsWork: Boolean = false,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val container: String? = null,
    @SerialName("queue_item_id") val queueItemId: Long? = null,
    val state: String? = null,
)

@Serializable
data class ConverterSearchResult(
    @SerialName("rating_key") val ratingKey: String,
    val type: String? = null,
    val title: String = "",
    val year: Int? = null,
    @SerialName("section_title") val sectionTitle: String? = null,
    @SerialName("leaf_count") val leafCount: Int? = null,
    val files: List<ConverterSearchFile> = emptyList(),
)

@Serializable
private data class SearchPage(val results: List<ConverterSearchResult> = emptyList())

@Serializable
data class EnqueueResult(
    val queued: Int = 0,
    @SerialName("already_compatible") val alreadyCompatible: Int = 0,
    @SerialName("not_in_library") val notInLibrary: Int = 0,
)

class ConverterException(message: String) : IOException(message)

@Singleton
class VideoConverterClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private suspend fun baseUrl(): String {
        val host = settingsRepository.lanHostFlow.first().trim()
        if (host.isBlank()) throw ConverterException("Daemon LAN host isn't set (Settings → Sidekick Daemon)")
        return "http://$host:$VIDEO_CONVERTER_PORT/api/converter"
    }

    private suspend fun call(path: String, method: String = "GET", body: String? = null): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl() + path)
                .header("X-Sidekick-Key", settingsRepository.lanApiKeyFlow.first())
            when (method) {
                "POST" -> builder.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            okHttpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> text
                    response.code == 401 -> throw ConverterException("Wrong API key for the video converter")
                    response.code == 409 -> throw ConverterException("That item changed state — refresh")
                    else -> throw ConverterException("Video converter error ${response.code}")
                }
            }
        }

    suspend fun status(): ConverterStatus = json.decodeFromString(call("/status"))

    suspend fun queue(limit: Int = 200): Pair<List<ConverterItem>, Int> {
        val page = json.decodeFromString<ItemsPage>(call("/queue?limit=$limit"))
        return page.items to (page.total ?: page.items.size)
    }

    suspend fun history(limit: Int = 200): List<ConverterItem> =
        json.decodeFromString<ItemsPage>(call("/history?limit=$limit")).items

    suspend fun moveToFront(id: Long) { call("/item/$id/front", "POST") }

    suspend fun retry(id: Long, force: Boolean = false) {
        call("/item/$id/retry" + if (force) "?force=1" else "", "POST")
    }

    suspend fun remove(id: Long) { call("/item/$id", "DELETE") }

    suspend fun search(query: String): List<ConverterSearchResult> =
        json.decodeFromString<SearchPage>(
            call("/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
        ).results

    suspend fun enqueue(ratingKey: String): EnqueueResult =
        json.decodeFromString(call("/enqueue", "POST", """{"rating_key":"$ratingKey"}"""))

    suspend fun setPaused(paused: Boolean) { call(if (paused) "/pause" else "/resume", "POST") }

    suspend fun rescan() { call("/scan", "POST") }
}
