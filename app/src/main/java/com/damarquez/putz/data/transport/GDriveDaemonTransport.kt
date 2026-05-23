package com.damarquez.putz.data.transport

import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.remote.GDriveManager
import kotlinx.coroutines.Dispatchers
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

    override suspend fun pollResponses(googleAccount: String): List<ResponseEnvelope> {
        val files = gDriveManager.listResponses(googleAccount)
        return files.mapNotNull { file ->
            val content = gDriveManager.downloadFileContent(googleAccount, file.id) ?: return@mapNotNull null
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
    }

    override suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope) {
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
        val status = runCatching {
            json.parseToJsonElement(content).jsonObject["status"]?.jsonPrimitive?.content?.uppercase()
        }.getOrNull() ?: return null
        return HeartbeatData(status)
    }

    override suspend fun getLibraryVersion(googleAccount: String): Long? =
        gDriveManager.getFileMetadata(googleAccount, "assets.db")?.second

    override suspend fun downloadMetadataDb(googleAccount: String, destination: File): Boolean {
        val result = gDriveManager.downloadMetadataDb(googleAccount, destination)
        return result is NetworkResult.Success
    }
}
