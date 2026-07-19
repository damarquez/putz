package com.damarquez.putz.data.transport

import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileSearchResult
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.remote.GDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GDriveDaemonTransport @Inject constructor(
    private val gDriveManager: GDriveManager,
) : DaemonTransport {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submitRequest(googleAccount: String, fileName: String, content: String, isPriority: Boolean): String? =
        gDriveManager.uploadRequest(googleAccount, fileName, content, isPriority)

    override suspend fun promoteRequestToPriority(googleAccount: String, gdriveRequestId: String): Boolean =
        gDriveManager.promoteRequestToPriority(googleAccount, gdriveRequestId)

    override suspend fun pollResponses(googleAccount: String, appId: String): List<ResponseEnvelope> {
        val files = gDriveManager.listResponses(googleAccount, appId)
        // Fetching each file's content is a separate network round-trip; done sequentially,
        // a backlog of hundreds of responses takes minutes to even finish listing before any
        // processing/cleanup starts — long enough that new responses keep arriving faster than
        // the backlog drains. Fetch in bounded-concurrency batches instead of one at a time
        // (bounded so we don't hammer the Drive API into rate-limiting us, which would make
        // things worse).
        return files.chunked(20).flatMap { chunk ->
            coroutineScope {
                chunk.map { file ->
                    async {
                        val content = gDriveManager.downloadFileContent(googleAccount, file.id) ?: return@async null
                        val putioFileId = runCatching {
                            json.parseToJsonElement(content).jsonObject["putio_file_id"]?.jsonPrimitive?.content?.toLong()
                        }.getOrNull()
                        ResponseEnvelope(
                            id = file.id,
                            putioFileId = putioFileId,
                            content = content,
                            source = ResponseEnvelope.Source.DRIVE,
                        )
                    }
                }.awaitAll()
            }.filterNotNull()
        }
    }

    override suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope, appId: String) {
        if (envelope.source == ResponseEnvelope.Source.DRIVE) {
            gDriveManager.deleteFile(googleAccount, envelope.id)
        }
    }

    override suspend fun getHeartbeat(googleAccount: String): HeartbeatData? {
        val fileId = runCatching {
            withContext(Dispatchers.IO) {
                val service = gDriveManager.getDriveService(googleAccount)
                val libFolderId = gDriveManager.getLibraryFolderId(service) ?: return@withContext null

                val rootResult = service.files().list()
                    .setQ("name = 'heartbeat.json' and '$libFolderId' in parents and trashed = false")
                    .setFields("files(id)")
                    .execute()
                    .files
                    ?.firstOrNull()
                    ?.id

                rootResult ?: run {
                    val integrationId = gDriveManager.findFolder(service, ".calibre_integration", libFolderId)
                        ?: return@withContext null
                    service.files().list()
                        .setQ("name = 'heartbeat.json' and '$integrationId' in parents and trashed = false")
                        .setFields("files(id)")
                        .execute()
                        .files
                        ?.firstOrNull()
                        ?.id
                }
            }
        }.getOrNull() ?: return null

        val content = gDriveManager.downloadFileContent(googleAccount, fileId) ?: return null
        val obj = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        val status = obj["status"]?.jsonPrimitive?.content?.uppercase() ?: return null
        val historyFileId = runCatching {
            obj["transfer_history_file_id"]?.jsonPrimitive?.content?.toLong()
        }.getOrNull()
        return HeartbeatData(status, historyFileId)
    }

    override suspend fun getLibraryVersion(googleAccount: String): Long? =
        gDriveManager.getFileMetadata(googleAccount, "assets.db")?.second

    override suspend fun downloadMetadataDb(googleAccount: String, destination: File): Boolean {
        val result = gDriveManager.downloadMetadataDb(googleAccount, destination)
        return result is NetworkResult.Success
    }

    // CONTRACT: FETCH_HISTORY_FILES — Drive-only fallback (LanDaemonTransport handles the fast
    // path); this daemon action needs real computation, so it goes through the generic
    // submit+poll queue like REGISTER_TRANSFER_HISTORY, just waited-on here instead of
    // fire-and-forget.
    override suspend fun getHistoryEntryFiles(googleAccount: String, appId: String, infoHash: String): List<HistoryEntryFile>? {
        val requestId = System.currentTimeMillis()
        val body = JsonObject(mapOf(
            "action" to JsonPrimitive("FETCH_HISTORY_FILES"),
            "putio_file_id" to JsonPrimitive(requestId),
            "info_hash" to JsonPrimitive(infoHash),
            "app_id" to JsonPrimitive(appId),
        ))
        submitRequest(googleAccount, "req_hist_files_$requestId.json", json.encodeToString(JsonObject.serializer(), body))
            ?: return null
        val content = awaitResponse(googleAccount, appId, requestId) ?: return null
        return runCatching {
            json.parseToJsonElement(content).jsonObject["files"]?.let {
                json.decodeFromJsonElement(ListSerializer(HistoryEntryFile.serializer()), it)
            }
        }.getOrNull()
    }

    // CONTRACT: SEARCH_HISTORY_FILES — same Drive-only fallback pattern as above.
    override suspend fun searchHistoryFiles(googleAccount: String, appId: String, query: String): List<HistoryFileSearchResult>? {
        val requestId = System.currentTimeMillis()
        val body = JsonObject(mapOf(
            "action" to JsonPrimitive("SEARCH_HISTORY_FILES"),
            "putio_file_id" to JsonPrimitive(requestId),
            "query" to JsonPrimitive(query),
            "app_id" to JsonPrimitive(appId),
        ))
        submitRequest(googleAccount, "req_hist_search_$requestId.json", json.encodeToString(JsonObject.serializer(), body))
            ?: return null
        val content = awaitResponse(googleAccount, appId, requestId) ?: return null
        return runCatching {
            json.parseToJsonElement(content).jsonObject["results"]?.let {
                json.decodeFromJsonElement(ListSerializer(HistoryFileSearchResult.serializer()), it)
            }
        }.getOrNull()
    }

    /** Bounded poll for a response matching [requestId] (the `putio_file_id` anchor this class
     *  mints for one-off computed requests that aren't tied to a real put.io transfer). Drive
     *  round-trips are inherently slow — this is only reached when LAN is unavailable. */
    private suspend fun awaitResponse(
        googleAccount: String,
        appId: String,
        requestId: Long,
        timeoutMs: Long = 30_000,
        pollIntervalMs: Long = 2_000,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val match = pollResponses(googleAccount, appId).firstOrNull { it.putioFileId == requestId }
            if (match != null) {
                acknowledgeResponse(googleAccount, match, appId)
                return match.content
            }
            delay(pollIntervalMs)
        }
        return null
    }
}
