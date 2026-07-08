package com.damarquez.putz.data.transport

import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.remote.GDriveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GDriveDaemonTransport @Inject constructor(
    private val gDriveManager: GDriveManager,
) : DaemonTransport {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submitRequest(googleAccount: String, fileName: String, content: String): String? =
        gDriveManager.uploadRequest(googleAccount, fileName, content)

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
}
