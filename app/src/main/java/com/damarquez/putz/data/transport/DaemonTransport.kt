package com.damarquez.putz.data.transport

import java.io.File

/**
 * Abstracts the IPC channel between Putz and the sidekick daemon.
 *
 * Implementations:
 *  - [GDriveDaemonTransport] — original path via Google Drive polling
 *  - [LanDaemonTransport]   — direct HTTP path when on Tailscale / LAN
 *  - [SmartDaemonTransport] — dual-write + fallback; always returns a Drive file ID for DB storage
 */
interface DaemonTransport {
    /** Upload a request JSON; returns the Drive file ID (used in DB as gdriveRequestId), or null on failure. */
    suspend fun submitRequest(googleAccount: String, fileName: String, content: String): String?

    /** Return all pending responses; each envelope contains the raw JSON and its delivery source. */
    suspend fun pollResponses(googleAccount: String): List<ResponseEnvelope>

    /** Acknowledge that a response was processed (delete from Drive / LAN buffer). */
    suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope)

    /** Return current daemon heartbeat status, or null if unavailable. */
    suspend fun getHeartbeat(googleAccount: String): HeartbeatData?

    /** Return a version token for the library (milliseconds since epoch), or null if unavailable. */
    suspend fun getLibraryVersion(googleAccount: String): Long?

    /** Download the Calibre metadata.db to [destination]; returns true on success. */
    suspend fun downloadMetadataDb(googleAccount: String, destination: File): Boolean
}

data class ResponseEnvelope(
    /** Drive file ID for DRIVE source; putio_file_id string for LAN source. */
    val id: String,
    /** Extracted from the JSON for dedup; may be null for probe/status responses. */
    val putioFileId: Long?,
    val content: String,
    val source: Source,
) {
    enum class Source { DRIVE, LAN }
}

data class HeartbeatData(
    val status: String,
    val historyFileId: Long? = null,
)
