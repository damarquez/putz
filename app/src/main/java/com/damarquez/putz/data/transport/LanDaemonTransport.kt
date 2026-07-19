package com.damarquez.putz.data.transport

import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileSearchResult
import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanDaemonTransport @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : DaemonTransport {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun baseUrl(): String {
        val host = settingsRepository.lanHostFlow.first().trim()
        val port = settingsRepository.lanPortFlow.first()
        return "http://$host:$port/api"
    }

    private suspend fun apiKey(): String = settingsRepository.lanApiKeyFlow.first()

    /** TCP reachability check with a short timeout. */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val host = settingsRepository.lanHostFlow.first().trim()
        val port = settingsRepository.lanPortFlow.first()
        if (host.isBlank()) return@withContext false
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2_000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** POST the request JSON to the daemon; returns the request_id on success, null on failure.
     *  [isPriority] has no effect here — a LAN-submitted request is dispatched immediately, never
     *  sitting in a queue for priority to matter. */
    override suspend fun submitRequest(googleAccount: String, fileName: String, content: String, isPriority: Boolean): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/request")
                .header("X-Sidekick-Key", apiKey())
                .post(content.toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use null
                        json.parseToJsonElement(body).jsonObject["request_id"]?.jsonPrimitive?.content
                    } else null
                }
            }.getOrNull()
        }

    // CONTRACT: priority requests lane — irrelevant over LAN, so always a no-op.
    override suspend fun promoteRequestToPriority(googleAccount: String, gdriveRequestId: String): Boolean = false

    override suspend fun pollResponses(googleAccount: String, appId: String): List<ResponseEnvelope> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/responses/$appId")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val putioFileId = runCatching {
                            json.parseToJsonElement(content).jsonObject["putio_file_id"]?.jsonPrimitive?.content?.toLong()
                        }.getOrNull()
                        ResponseEnvelope(
                            id = id,
                            putioFileId = putioFileId,
                            content = content,
                            source = ResponseEnvelope.Source.LAN,
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope, appId: String) {
        if (envelope.source != ResponseEnvelope.Source.LAN) return
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/response/$appId/${envelope.id}")
                .header("X-Sidekick-Key", apiKey())
                .delete()
                .build()
            runCatching { okHttpClient.newCall(request).execute().close() }
        }
    }

    override suspend fun getHeartbeat(googleAccount: String): HeartbeatData? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/heartbeat")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val obj = json.parseToJsonElement(body).jsonObject
                    val status = obj["status"]?.jsonPrimitive?.content?.uppercase() ?: return@use null
                    val historyFileId = runCatching {
                        obj["transfer_history_file_id"]?.jsonPrimitive?.content?.toLong()
                    }.getOrNull()
                    HeartbeatData(status, historyFileId)
                }
            }.getOrNull()
        }

    override suspend fun getLibraryVersion(googleAccount: String): Long? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/library/version")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val versionStr = json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content
                        ?: return@use null
                    if (versionStr == "unknown") return@use null
                    Instant.parse(versionStr).toEpochMilli()
                }
            }.getOrNull()
        }

    // CONTRACT: protection split — queries the daemon's live, unsplit metadata.db directly,
    // so it can find a protected/encrypted book that a downloaded metadata.db copy never
    // will (see protection_split.py). Used as a fallback when a local UUID lookup misses.
    data class LanBookMatch(val id: Long, val title: String, val author: String, val tags: String)

    suspend fun getBookByUuid(uuid: String): LanBookMatch? =
        withContext(Dispatchers.IO) {
            val encodedUuid = java.net.URLEncoder.encode(uuid, "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl()}/library/book_by_uuid/$encodedUuid")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    val obj = json.parseToJsonElement(body).jsonObject
                    LanBookMatch(
                        id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@use null,
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        author = obj["author"]?.jsonPrimitive?.content ?: "",
                        tags = obj["tags"]?.jsonPrimitive?.content ?: "",
                    )
                }
            }.getOrNull()
        }

    override suspend fun downloadMetadataDb(googleAccount: String, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/library/metadata.db")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                    true
                }
            }.getOrDefault(false)
        }

    // CONTRACT: FETCH_HISTORY_FILES
    override suspend fun getHistoryEntryFiles(googleAccount: String, appId: String, infoHash: String): List<HistoryEntryFile>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/history/$infoHash/files")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    json.parseToJsonElement(body).jsonObject["files"]?.let {
                        json.decodeFromJsonElement(ListSerializer(HistoryEntryFile.serializer()), it)
                    }
                }
            }.getOrNull()
        }

    // CONTRACT: SEARCH_HISTORY_FILES
    override suspend fun searchHistoryFiles(googleAccount: String, appId: String, query: String): List<HistoryFileSearchResult>? =
        withContext(Dispatchers.IO) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl()}/history/search?q=$encodedQuery")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    json.parseToJsonElement(body).jsonObject["results"]?.let {
                        json.decodeFromJsonElement(ListSerializer(HistoryFileSearchResult.serializer()), it)
                    }
                }
            }.getOrNull()
        }

    /** Download a book file directly from the library. */
    suspend fun downloadBookFile(bookId: Int, format: String, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/library/book/$bookId/${format.lowercase()}")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                    true
                }
            }.getOrDefault(false)
        }

    /** Stream a file from the local put.io mirror by its put.io file ID.
     *  For new-format stubs, pass [localPath] (from stub JSON) so the daemon can locate the file
     *  directly without a legacy index lookup.
     *  Returns null on success, or a human-readable error string on failure. */
    suspend fun downloadMirrorFile(putioFileId: Long, destination: File, localPath: String? = null): String? =
        withContext(Dispatchers.IO) {
            val base = "${baseUrl()}/mirror/file/$putioFileId"
            val url = if (localPath != null) "$base?local_path=${java.net.URLEncoder.encode(localPath, "UTF-8")}" else base
            val request = Request.Builder()
                .url(url)
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val msg = "HTTP ${response.code} — $url"
                        android.util.Log.w("LanDaemonTransport", "downloadMirrorFile($putioFileId) failed: $msg")
                        return@use msg
                    }
                    val body = response.body ?: return@use "empty response — $url"
                    destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                    null
                }
            }.getOrElse { e ->
                val msg = "${e.message} — $url"
                android.util.Log.w("LanDaemonTransport", "downloadMirrorFile($putioFileId) exception: $msg")
                msg
            }
        }

    // CONTRACT: self-update — cheap check (no download): the daemon reports the NAS-delivered
    // APK's mtime, an epoch-seconds int directly comparable to BuildConfig.VERSION_CODE.
    suspend fun getAppVersion(appName: String): Long? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/apps/$appName/version")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content?.toLongOrNull()
                }
            }.getOrNull()
        }

    suspend fun downloadAppApk(appName: String, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/apps/$appName/apk")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                    true
                }
            }.getOrDefault(false)
        }

    suspend fun downloadAssetsDb(destination: File): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/library/assets.db")
                .header("X-Sidekick-Key", apiKey())
                .get()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                    true
                }
            }.getOrDefault(false)
        }
}
