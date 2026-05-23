package com.damarquez.putz.data.transport

import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dual-write + fallback transport.
 *
 * For request submission:
 *   1. If LAN is enabled and reachable: POST to daemon directly (fast path, no Drive wait).
 *   2. Always upload to Drive (durable store). If LAN succeeded, the Drive copy carries
 *      `lan_attempted: true` so the daemon knows to skip it once the handled_set is written.
 *   3. Returns the Drive file ID (used in the DB as gdriveRequestId).
 *
 * For response polling:
 *   - If LAN is available: poll LAN buffer first (near-instant), then Drive (cleanup + fallback).
 *   - Deduplication by putio_file_id prevents double-processing.
 *
 * Cloud-only mode (LAN disabled): delegates entirely to [GDriveDaemonTransport].
 */
@Singleton
class SmartDaemonTransport @Inject constructor(
    private val drive: GDriveDaemonTransport,
    private val lan: LanDaemonTransport,
    private val settingsRepository: SettingsRepository,
) : DaemonTransport {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun lanEnabled(): Boolean = settingsRepository.lanEnabledFlow.first()

    override suspend fun submitRequest(googleAccount: String, fileName: String, content: String): String? {
        if (lanEnabled() && lan.isReachable()) {
            try {
                lan.submitRequest(googleAccount, fileName, content)
                // LAN delivery succeeded — mark Drive copy so daemon can skip if already handled
                return drive.submitRequest(googleAccount, fileName, injectLanFlag(content))
            } catch (_: Exception) {
                // Fall through to Drive-only
            }
        }
        return drive.submitRequest(googleAccount, fileName, content)
    }

    override suspend fun pollResponses(googleAccount: String): List<ResponseEnvelope> {
        val all = mutableListOf<ResponseEnvelope>()

        if (lanEnabled() && lan.isReachable()) {
            runCatching { all += lan.pollResponses(googleAccount) }
        }

        val lanIds = all.mapNotNull { it.putioFileId }.toSet()
        val driveEnvelopes = runCatching { drive.pollResponses(googleAccount) }.getOrDefault(emptyList())

        // Always add Drive envelopes; CalibreRepository's isNewerStatus guard prevents double-processing
        all += driveEnvelopes

        return all
    }

    override suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope) {
        when (envelope.source) {
            ResponseEnvelope.Source.LAN -> {
                runCatching { lan.acknowledgeResponse(googleAccount, envelope) }
            }
            ResponseEnvelope.Source.DRIVE -> {
                runCatching { drive.acknowledgeResponse(googleAccount, envelope) }
            }
        }
    }

    override suspend fun getHeartbeat(googleAccount: String): HeartbeatData? {
        if (lanEnabled() && lan.isReachable()) {
            runCatching { lan.getHeartbeat(googleAccount) }.getOrNull()?.let { return it }
        }
        return drive.getHeartbeat(googleAccount)
    }

    override suspend fun getLibraryVersion(googleAccount: String): Long? {
        if (lanEnabled() && lan.isReachable()) {
            runCatching { lan.getLibraryVersion(googleAccount) }.getOrNull()?.let { return it }
        }
        return drive.getLibraryVersion(googleAccount)
    }

    override suspend fun downloadMetadataDb(googleAccount: String, destination: File): Boolean {
        if (lanEnabled() && lan.isReachable()) {
            runCatching {
                if (lan.downloadMetadataDb(googleAccount, destination)) return true
            }
        }
        return drive.downloadMetadataDb(googleAccount, destination)
    }

    private fun injectLanFlag(content: String): String = try {
        val obj = json.parseToJsonElement(content).jsonObject
        val modified = JsonObject(obj + ("lan_attempted" to JsonPrimitive(true)))
        json.encodeToString(JsonObject.serializer(), modified)
    } catch (_: Exception) {
        content
    }
}
