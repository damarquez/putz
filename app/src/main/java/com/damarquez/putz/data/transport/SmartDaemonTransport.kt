package com.damarquez.putz.data.transport

import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileSearchResult
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

    override suspend fun submitRequest(googleAccount: String, fileName: String, content: String, isPriority: Boolean): String? {
        if (lanEnabled() && lan.isReachable()) {
            try {
                lan.submitRequest(googleAccount, fileName, content, isPriority)
                // LAN delivery succeeded — mark Drive copy so daemon can skip if already handled
                return drive.submitRequest(googleAccount, fileName, injectLanFlag(content), isPriority)
            } catch (_: Exception) {
                // Fall through to Drive-only
            }
        }
        return drive.submitRequest(googleAccount, fileName, content, isPriority)
    }

    // CONTRACT: priority requests lane — always via Drive, since gdriveRequestId (the only handle
    // we keep on a pending request) is always a Drive file ID regardless of which transport
    // originally submitted it. If the request was actually delivered via LAN, the daemon's own
    // priority poll detects the in-flight LAN copy and redirects the promotion internally — see
    // putz_manager._claim_priority_requests — so this doesn't need to know or care which lane
    // originally handled the request.
    override suspend fun promoteRequestToPriority(googleAccount: String, gdriveRequestId: String): Boolean =
        drive.promoteRequestToPriority(googleAccount, gdriveRequestId)

    override suspend fun pollResponses(
        googleAccount: String,
        appId: String,
        onCleanupProgress: ((current: Int, total: Int) -> Unit)?,
        onFetchProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<ResponseEnvelope> {
        val all = mutableListOf<ResponseEnvelope>()

        if (lanEnabled() && lan.isReachable()) {
            runCatching { all += lan.pollResponses(googleAccount, appId) }
        }

        val driveEnvelopes = runCatching {
            drive.pollResponses(googleAccount, appId, onCleanupProgress, onFetchProgress)
        }.getOrDefault(emptyList())

        // Always add Drive envelopes; CalibreRepository's isNewerStatus guard prevents double-processing
        all += driveEnvelopes

        return all
    }

    override suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope, appId: String) {
        when (envelope.source) {
            ResponseEnvelope.Source.LAN -> {
                runCatching { lan.acknowledgeResponse(googleAccount, envelope, appId) }
            }
            ResponseEnvelope.Source.DRIVE -> {
                runCatching { drive.acknowledgeResponse(googleAccount, envelope, appId) }
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

    override suspend fun getHistoryEntryFiles(googleAccount: String, appId: String, infoHash: String): List<HistoryEntryFile>? {
        if (lanEnabled() && lan.isReachable()) {
            runCatching { lan.getHistoryEntryFiles(googleAccount, appId, infoHash) }.getOrNull()?.let { return it }
        }
        return drive.getHistoryEntryFiles(googleAccount, appId, infoHash)
    }

    override suspend fun searchHistoryFiles(googleAccount: String, appId: String, query: String): List<HistoryFileSearchResult>? {
        if (lanEnabled() && lan.isReachable()) {
            runCatching { lan.searchHistoryFiles(googleAccount, appId, query) }.getOrNull()?.let { return it }
        }
        return drive.searchHistoryFiles(googleAccount, appId, query)
    }

    override suspend fun forgetHistoryEntry(googleAccount: String, appId: String, infoHash: String): Boolean {
        if (lanEnabled() && lan.isReachable()) {
            if (runCatching { lan.forgetHistoryEntry(googleAccount, appId, infoHash) }.getOrDefault(false)) return true
        }
        return drive.forgetHistoryEntry(googleAccount, appId, infoHash)
    }

    private fun injectLanFlag(content: String): String = try {
        val obj = json.parseToJsonElement(content).jsonObject
        val modified = JsonObject(obj + ("lan_attempted" to JsonPrimitive(true)))
        json.encodeToString(JsonObject.serializer(), modified)
    } catch (_: Exception) {
        content
    }
}
