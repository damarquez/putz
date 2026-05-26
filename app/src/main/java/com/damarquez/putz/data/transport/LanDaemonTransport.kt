package com.damarquez.putz.data.transport

import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    /** POST the request JSON to the daemon; returns the request_id on success, null on failure. */
    override suspend fun submitRequest(googleAccount: String, fileName: String, content: String): String? =
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

    override suspend fun pollResponses(googleAccount: String): List<ResponseEnvelope> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/responses")
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

    override suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope) {
        if (envelope.source != ResponseEnvelope.Source.LAN) return
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}/response/${envelope.id}")
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
                    val status = json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content
                        ?.uppercase() ?: return@use null
                    HeartbeatData(status)
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
