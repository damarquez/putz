package com.damarquez.putz.data.transport

import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileSearchResult
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
    /** Upload a request JSON; returns the Drive file ID (used in DB as gdriveRequestId), or null on
     *  failure. [isPriority] routes it into the daemon's priority lane (see CONTRACTS.md §17) — no
     *  effect over the LAN path, since that's already dispatched immediately regardless. */
    suspend fun submitRequest(googleAccount: String, fileName: String, content: String, isPriority: Boolean = false): String?

    // CONTRACT: priority requests lane — promotes an already-submitted, not-yet-claimed request in
    // place by moving its Drive file into requests/priority/. No-op (returns false) over LAN, since
    // a LAN-submitted request is already dispatched immediately and never sits queued. If the Drive
    // mirror copy of a LAN-submitted request gets promoted this way, the daemon's own priority poll
    // detects the in-flight LAN copy and redirects the promotion internally — see
    // putz_manager._claim_priority_requests — so no client-side LAN-specific handling is needed.
    suspend fun promoteRequestToPriority(googleAccount: String, gdriveRequestId: String): Boolean

    /** Return all pending responses for this app instance; each envelope contains the raw JSON and its delivery source. */
    suspend fun pollResponses(googleAccount: String, appId: String): List<ResponseEnvelope>

    /** Acknowledge that a response was processed (delete from Drive / LAN buffer). */
    suspend fun acknowledgeResponse(googleAccount: String, envelope: ResponseEnvelope, appId: String)

    /** Return current daemon heartbeat status, or null if unavailable. */
    suspend fun getHeartbeat(googleAccount: String): HeartbeatData?

    /** Return a version token for the library (milliseconds since epoch), or null if unavailable. */
    suspend fun getLibraryVersion(googleAccount: String): Long?

    /** Download the Calibre metadata.db to [destination]; returns true on success. */
    suspend fun downloadMetadataDb(googleAccount: String, destination: File): Boolean

    // CONTRACT: FETCH_HISTORY_FILES — per-file listing is never in the routine history JSON
    // (see TransferHistoryRepository/history sync); fetched on demand for the detail view.
    suspend fun getHistoryEntryFiles(googleAccount: String, appId: String, infoHash: String): List<HistoryEntryFile>?

    // CONTRACT: SEARCH_HISTORY_FILES — server-side (daemon SQLite) file-name search, backing the
    // History screen's "search in files" toggle.
    suspend fun searchHistoryFiles(googleAccount: String, appId: String, query: String): List<HistoryFileSearchResult>?
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
