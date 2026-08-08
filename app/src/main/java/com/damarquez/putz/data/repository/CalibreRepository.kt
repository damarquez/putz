package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.CalibreTransferDao
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.data.transport.DaemonTransport
import com.damarquez.putz.data.transport.ResponseEnvelope
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.util.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
data class TransferDeleteProgress(
    val message: String,
    val current: Int,
    val total: Int,
)

enum class SyncPhase {
    PROBING_DAEMON,
    PULLING_RESPONSES,
    DOWNLOADING_METADATA,
    VERIFYING_TRANSFERS,
    CHECKING_HEARTBEAT,
}

data class SyncProgress(
    val phase: SyncPhase,
    val message: String,
    val current: Int? = null,
    val total: Int? = null,
)

// CONTRACT: ADD_BOOK_BATCH
@Serializable
data class CalibreBatchItem(
    val type: String, // "SINGLE", "PACK", "ARCHIVE", "ARCHIVE_ENTRY", "PDF_PACK", "EPUB_PACK"
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
    val files: List<AudiobookFile>? = null, // For PACK, PDF_PACK, IMAGE_PDF_PACK, etc. — flat/unchaptered
    val groups: List<PackGroup>? = null, // For IMAGE_PDF_PACK (merge framework) — chaptered, one PackGroup per chapter
    val archiveMode: String? = null, // For ARCHIVE
    val use_local: Boolean? = null,  // When true the daemon uses the local synced copy; no download needed
    val local_path: String? = null,  // CONTRACT: stub convention — relative path within the local mirror repo
    val smb_path: String? = null,    // When set the daemon reads directly from this UNC path; no download needed
    val archive_entry: String? = null, // For ARCHIVE_ENTRY: path of the entry within the archive file
    val protected: Boolean? = null,  // When true the daemon encrypts the file before adding to Calibre
    // CONTRACT: Merge framework — 1-100 JPEG quality for IMAGE_PDF_PACK/CBR_PDF_PACK's
    // raster-image-to-PDF step (ImagePdfPackJob/CbrPdfPackJob in pack_jobs.py). null (default)
    // keeps the original lossless-but-large embed; every other pack engine ignores this field.
    val image_quality: Int? = null,
    // CONTRACT: ADD_BOOK_BATCH "to PDF" — when true and fileName's format is convertible
    // (see MetadataUtils.canConvertToPdf), the daemon converts it to PDF via ebook-convert
    // and adds the PDF as an additional format alongside the original (never replacing it).
    // If fileName already carries the "_bkp" alt-version suffix, the derived PDF is added as
    // PDF_BKP instead of PDF, so it never clobbers a plain PDF already on the book.
    val convert_to_pdf: Boolean? = null,
    // CONTRACT: source-batch grouping — each AudiobookFile.sourceBatch tags which assembly
    // append contributed it. Null = legacy (treat as batch 1). When multiple distinct batches
    // are present the daemon creates one chapter/folder/section per batch in the merged output;
    // single-batch items are flattened to the same output as pre-batch requests.
    // User-edited labels for each batch index (key = batch index as string in JSON); absent
    // entries default to "Part N" at build time.
    val sourceBatchLabels: Map<Int, String>? = null,
)
// CONTRACT: ADD_BOOK_BATCH, probe pattern
@Serializable
data class CalibreBatchRequest(
    val action: String = "ADD_BOOK_BATCH",
    val putio_file_id: Long, // Anchor ID
    val title: String,
    val author: String,
    val items: List<CalibreBatchItem>,
    val is_probe: Boolean? = null,
    val calibre_book_id: Long? = null, // For REPLACE_COVER
    val calibre_book_uuid: String? = null, // For targeting existing book
    val comments: String? = null, // For UPDATE_COMMENTS
    val tags: String? = null, // For UPDATE_COMMENTS
    val source_format: String? = null, // For CONVERT_FORMAT
    val target_format: String? = null, // For CONVERT_FORMAT
    // For PROTECT_BOOK — when true, don't replace the existing cover. Also reused for
    // ADD_BOOK_BATCH: when true and a protected item is being added, the daemon skips
    // generating its default obfuscated cover, since a real cover is already staged to
    // follow via REPLACE_COVER (see attachClipboardCoverToAssembly/pollResponses).
    val keep_cover: Boolean? = null,
    val app_id: String? = null, // Device ID — daemon echoes back so each device only reads its own responses
)

// CONTRACT: ADD_BOOK_BATCH
@Serializable
data class AudiobookFile(
    val putio_file_id: Long,
    val fileName: String,
    val download_url: String? = null,
    val smb_path: String? = null,
    val use_local: Boolean? = null,
    val local_path: String? = null,  // CONTRACT: stub convention — relative path within the local mirror repo
    // CONTRACT: merge framework — archive-entry source (see CONTRACTS.md "Merge framework").
    // When set, download_url/smb_path/use_local/local_path above resolve the CONTAINING ARCHIVE
    // (named archive_file_name), and the daemon extracts archive_entry from it server-side —
    // fileName above stays this entry's own name, not the archive's. No producer wires these yet;
    // this is the forward-compatible hook for a future "merge from inside an archive" UI.
    val archive_entry: String? = null,
    val archive_file_name: String? = null,
    // CONTRACT: source-batch grouping — tags which assembly append this file came from.
    // Null = legacy file (treated as batch 1 everywhere). Set by addMergeTransfer (batch 1)
    // and mergeIntoAssemblyItem (batch N+1) — never by resolveForMerge.
    val sourceBatch: Int? = null,
)

// CONTRACT: ADD_BOOK_BATCH — merge framework (see CONTRACTS.md "Merge framework")
@Serializable
data class PackGroup(
    val label: String, // Chapter/bookmark title in the merged output
    val files: List<AudiobookFile>,
)

// CONTRACT: response schema, GLOBAL_STATUS_PROBE
@Serializable
data class CalibreResponse(
    val action: String,
    val putio_file_id: Long? = null,
    val status: String,
    val error: String? = null,
    val daemon_status: String? = null, // "IDLE" or "WORKING"
    val calibre_book_uuid: String? = null,
    val calibre_book_id: Int? = null,
    val warnings: List<String>? = null,
    val app_id: String? = null, // Echoed from request; used to route responses to the correct device
    // CONTRACT: probe pattern — echoed by the daemon when this response answers an is_probe
    // request, so pollResponses can tell a manual re-check apart from a normal completion.
    val is_probe: Boolean? = null,
    // CONTRACT: request timing — present on COMPLETED when the daemon tracked this attempt's
    // start time; elapsed seconds from when a worker actually began fulfilling the request
    // (not from when it first arrived/was queued). See putz_manager.py's send_response().
    val duration_seconds: Double? = null,
)

data class CalibreBookMatch(
    val id: Long,
    val title: String,
    val author: String,
    val tags: String = "",
)

// CONTRACT: BATCH_ADD_TAGS
@Serializable
data class BatchAddTagsRequest(
    val action: String = "BATCH_ADD_TAGS",
    val putio_file_id: Long,
    val calibre_book_uuids: List<String>,
    val tags: String,
    val app_id: String? = null,
)

// CONTRACT: SEND_TO_PLEX
@Serializable
data class PlexAssemblyItem(
    val putio_file_id: Long,     // CONTRACT: stub convention — original file ID (from stub filename for synced files)
    val fileName: String,
    val item_type: String = "MOVIE", // "MOVIE" or "SUBTITLE"
    val language: String? = null,
    val use_local: Boolean? = null,  // When true the daemon resolves the file from the local mirror repo
    val local_path: String? = null,  // CONTRACT: stub convention — relative path within the local mirror repo
    val stub_putio_id: Long? = null, // CONTRACT: stub convention — actual put.io ID of the stub (for daemon to delete after processing)
)

@Serializable
data class PlexBatchData(
    val movie_title: String,
    val year: String,
    val dest_path: String,
    val items: List<PlexAssemblyItem>,
    val create_folder: Boolean = true,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(s: String): PlexBatchData? = try { json.decodeFromString(s) } catch (_: Exception) { null }
    }
}

// CONTRACT: SEND_TO_PLEX
@Serializable
data class PlexTransferRequest(
    val action: String = "SEND_TO_PLEX",
    val putio_file_id: Long,
    val movie_title: String,
    val year: String,
    val dest_path: String,
    val items: List<PlexAssemblyItem>,
    val create_folder: Boolean = true,
    val app_id: String? = null,
)

// CONTRACT: ADD_SUBTITLE_TO_MOVIE
@Serializable
data class PlexAddSubtitleRequest(
    val action: String = "ADD_SUBTITLE_TO_MOVIE",
    val putio_file_id: Long,         // CONTRACT: stub convention — original file ID (from stub filename for synced files)
    val language: String,
    val movie_folder_path: String,
    val movie_file_name: String,
    val local_path: String? = null,  // CONTRACT: stub convention — relative path within the local mirror repo
    val stub_putio_id: Long? = null, // CONTRACT: stub convention — actual put.io ID of the stub (for daemon to delete after processing)
    val app_id: String? = null,
)

// CONTRACT: SEND_TO_PLEXAMP
@Serializable
data class PlexampItem(
    val putio_file_id: Long,     // CONTRACT: stub convention — original file ID (from stub filename for synced files)
    val fileName: String,
    val use_local: Boolean? = null,
    val local_path: String? = null,
    val stub_putio_id: Long? = null, // CONTRACT: stub convention — actual put.io ID of the stub (for daemon to delete after processing)
)

@Serializable
data class PlexampTransferRequest(
    val action: String = "SEND_TO_PLEXAMP",
    val putio_file_id: Long,
    val artist_name: String,
    val album_name: String,
    val create_folder: Boolean = true,
    val dest_path: String = "",
    val items: List<PlexampItem>,
    val app_id: String? = null,
)

// CONTRACT: PRIORITY_PUTIO_SYNC
@Serializable
data class PrioritySyncRequest(
    val action: String = "PRIORITY_PUTIO_SYNC",
    val putio_file_id: Long,
    val app_id: String? = null,
)

// CONTRACT: FUSE_BOOKS
@Serializable
data class FuseFormatEntry(
    val format: String,
    val source_book_id: Long,
)

@Serializable
data class FuseMetadata(
    val title: String,
    val authors: String,
    val publisher: String? = null,
    val pubdate: String? = null,
    val series: String? = null,
    val series_index: Float? = null,
    val language: String? = null,
    val rating: Int? = null,
    val tags: List<String>? = null,
    val comments: String? = null,
)

// CONTRACT: FUSE_BOOKS / JOIN_BOOKS — one request shape shared by both actions (the "parse deep
// link -> build JSON -> upload" plumbing is identical either way). `action` picks which; `formats`
// is FUSE_BOOKS-only, `join_format`/`join_output_format` are JOIN_BOOKS-only.
@Serializable
data class FuseBooksRequest(
    val action: String = "FUSE_BOOKS",
    val putio_file_id: Long,
    val source_book_ids: List<Long>,
    val cover_source_book_id: Long?,
    val metadata: FuseMetadata,
    val formats: List<FuseFormatEntry>? = null,
    val join_format: String? = null,
    val join_output_format: String? = null,
    val app_id: String? = null,
)

// CONTRACT: MOVE_FORMAT — moves one format from an existing source book to an existing
// destination book. Unlike FUSE_BOOKS/JOIN_BOOKS, both books already exist (keyed by UUID,
// not a Room-local Long id), and the daemon — not this client — decides whether the format
// lands in the destination's base slot or its "_BKP" alternate; see CONTRACTS.md §36.
@Serializable
data class MoveFormatRequest(
    val action: String = "MOVE_FORMAT",
    val putio_file_id: Long,
    val source_book_uuid: String,
    val dest_book_uuid: String,
    val format: String,
    val app_id: String? = null,
)

// CONTRACT: MANAGE_VIRTUAL_LIBRARY
@Serializable
data class ManageVirtualLibraryRequest(
    val action: String = "MANAGE_VIRTUAL_LIBRARY",
    val putio_file_id: Long,
    val operation: String,
    val name: String,
    val new_name: String? = null,
    val search_query: String? = null,
    val app_id: String? = null,
)

// CONTRACT: RENAME_AUTHOR
@Serializable
data class RenameAuthorRequest(
    val action: String = "RENAME_AUTHOR",
    val putio_file_id: Long,
    val author_id: Long,
    val new_name: String,
    val app_id: String? = null,
)

@Serializable
data class GlobalStatusProbeRequest(
    val action: String = "GLOBAL_STATUS_PROBE",
    val app_id: String? = null,
)

// CONTRACT: SET_PAGE_COUNT
@Serializable
data class SetPageCountRequest(
    val action: String = "SET_PAGE_COUNT",
    val putio_file_id: Long,
    val calibre_book_uuid: String,
    val page_count: Int,
    val app_id: String? = null,
)

// CONTRACT: MARK_BOOK_FOR_DELETION
@Serializable
data class MarkBookForDeletionRequest(
    val action: String = "MARK_BOOK_FOR_DELETION",
    val putio_file_id: Long,
    val calibre_book_uuid: String,
    val app_id: String? = null,
)

// CONTRACT: MARK_FORMATS_FOR_DELETION
@Serializable
data class MarkFormatsForDeletionRequest(
    val action: String = "MARK_FORMATS_FOR_DELETION",
    val putio_file_id: Long,
    val calibre_book_uuid: String,
    val formats: List<String>,
    val app_id: String? = null,
)

// CONTRACT: CONFIRM_DELETE_BOOK
@Serializable
data class ConfirmDeleteBookRequest(
    val action: String = "CONFIRM_DELETE_BOOK",
    val putio_file_id: Long,
    val calibre_book_uuid: String,
    val app_id: String? = null,
)

// CONTRACT: CONFIRM_DELETE_FORMATS
@Serializable
data class ConfirmDeleteFormatsRequest(
    val action: String = "CONFIRM_DELETE_FORMATS",
    val putio_file_id: Long,
    val calibre_book_uuid: String,
    val formats: List<String>,
    val app_id: String? = null,
)

// CONTRACT: CANCEL_DELETION
@Serializable
data class CancelDeletionRequest(
    val action: String = "CANCEL_DELETION",
    val putio_file_id: Long,
    val calibre_book_uuid: String,
    val app_id: String? = null,
)

// CONTRACT: REGISTER_TRANSFER_HISTORY
@Serializable
data class RegisterHistoryRequest(
    val action: String = "REGISTER_TRANSFER_HISTORY",
    val putio_file_id: Long,
    val info_hash: String,
    val label: String,
    // Epoch-ms when this label was last explicitly set by the user.
    // Null / 0 means "background poll" — the daemon keeps whichever label is newer.
    val label_updated_at: Long? = null,
    val putio_name: String? = null,
    val magnet_uri: String? = null,
    val putio_id: Long? = null,
    val status: String,
    val app_id: String? = null,
)

@Singleton
class CalibreRepository @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val calibreTransferDao: CalibreTransferDao,
    private val gDriveManager: GDriveManager,
    private val daemonTransport: DaemonTransport,
    private val putioApiClient: PutioApiClient,
    private val secureStorage: SecureStorage,
    private val settingsRepository: com.damarquez.putz.settings.SettingsRepository,
    private val lanDaemonTransport: com.damarquez.putz.data.transport.LanDaemonTransport,
    @com.damarquez.putz.di.ApplicationScope private val appScope: CoroutineScope,
) {
    private val _daemonStatus = MutableStateFlow<String?>(null)
    val daemonStatus = _daemonStatus.asStateFlow()

    // CONTRACT: Putz's own Drive inspection (GDriveManager.countPendingRequests), not the
    // daemon's — see CalibreTransfersScreen's "Daemon: ... / N Drive requests" label. Kept
    // separate from _daemonStatus deliberately; the two must never be derived from each other.
    private val _driveRequestCount = MutableStateFlow<Int?>(null)
    val driveRequestCount = _driveRequestCount.asStateFlow()

    suspend fun refreshDriveRequestCount(googleAccount: String) {
        gDriveManager.countPendingRequests(googleAccount)?.let { _driveRequestCount.value = it }
    }

    private val _uploadProgress = MutableStateFlow<Map<Long, String>>(emptyMap())
    val uploadProgress = _uploadProgress.asStateFlow()

    private val _pendingAssemblyAppends = MutableStateFlow<Set<Long>>(emptySet())
    val pendingAssemblyAppends = _pendingAssemblyAppends.asStateFlow()

    private val _deleteProgress = MutableStateFlow<TransferDeleteProgress?>(null)
    val deleteProgress = _deleteProgress.asStateFlow()

    // CONTRACT: probe pattern. SmartDaemonTransport deliberately dual-submits requests over
    // LAN+Drive when LAN is reachable, and dual-polls responses from both channels too — by
    // design (see its kdoc), relying on pollResponses' status-comparison guards to make
    // re-processing the same logical response harmless. That's fine for a plain status update
    // (re-applying COMPLETED is a no-op) but pollResponses' is_probe branch counts each delivery
    // as a check, so a manual probe sent once could be counted twice (once per channel) or more
    // (if Drive redelivers an un-acked response across a couple of poll cycles before the ack
    // lands). Track in-flight probes here so only the first response for a given attempt counts;
    // a duplicate delivery of that same response is then correctly treated as a re-confirmation,
    // not a new check. Synchronized since LAN/Drive polling can run concurrently.
    private val probesAwaitingResponse = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    // GlobalSyncViewModel's background loop and a manually-triggered sync (CalibreTransfersViewModel
    // .syncMetadata) can both call pollResponses() around the same time. Without this, both would
    // fetch/process/ack the same Drive backlog concurrently — duplicated network round-trips
    // (turning a 30s backlog drain into 10+ minutes), spurious delete-already-gone 404s, and
    // concurrent read-modify-write on the same transfer rows (a coroutine's `transfer.copy(...)`
    // is based on a snapshot read at the start of its own processing, so one call finishing after
    // the other can silently clobber a field the other just wrote, e.g. libraryVerified flipping
    // back to false). The mutex makes pollResponses single-flight: a concurrent caller just waits
    // for the in-flight run to finish (which will have already drained whatever it needed to)
    // instead of starting a redundant second pass over the same backlog.
    private val pollResponsesMutex = Mutex()

    // Live status for whichever screen (Transfers or Settings > Libraries & Sync) has a sync in
    // flight — both trigger the same performFullSync() and observe this same flow, so progress
    // shows up no matter which one kicked it off. Null means no sync is currently running.
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    // Guards the whole multi-step sync (not just pollResponses) so two taps — one from each
    // screen, or a double-tap — can't run the same sequence of Drive round-trips concurrently.
    private val fullSyncMutex = Mutex()

    // The actual sync work runs on [appScope], not whichever ViewModel's coroutine called
    // performFullSync() — a plain viewModelScope job used to get cancelled the instant its
    // ViewModel's NavBackStackEntry was popped (switching screens), silently killing a sync that
    // might be minutes into draining a large Drive response backlog. A concurrent caller (the
    // other screen, or a double-tap) joins this same in-flight Deferred instead of starting a
    // redundant second pass — `isActive` naturally goes false once it completes, so the next call
    // starts fresh. Guarded by syncLaunchMutex since check-then-launch isn't otherwise atomic.
    private var activeSyncJob: Deferred<NetworkResult<Unit>>? = null
    private val syncLaunchMutex = Mutex()

    fun updateDeleteProgress(progress: TransferDeleteProgress?) {
        _deleteProgress.value = progress
    }

    /** Single source of truth for "sync now", used by both CalibreTransfersViewModel's sync
     *  button and Settings > Libraries & Sync. Reports phase-by-phase progress via
     *  [syncProgress] instead of the old opaque spinner. Survives the calling screen being
     *  navigated away from — see [activeSyncJob]. */
    suspend fun performFullSync(googleAccount: String): NetworkResult<Unit> {
        val job = syncLaunchMutex.withLock {
            activeSyncJob?.takeIf { it.isActive } ?: appScope.async {
                performFullSyncInternal(googleAccount)
            }.also { activeSyncJob = it }
        }
        return job.await()
    }

    private suspend fun performFullSyncInternal(googleAccount: String): NetworkResult<Unit> = fullSyncMutex.withLock {
        try {
            _syncProgress.value = SyncProgress(SyncPhase.PROBING_DAEMON, "Checking daemon status...")
            sendGlobalStatusProbe(googleAccount)

            // pollResponses() reports its own live progress into _syncProgress now (see its body) —
            // it has to, since pollResponsesMutex can make this call block for the entire duration
            // of a concurrent GlobalSyncViewModel background poll instead of doing any work itself.
            // Setting a static message here would just freeze the banner for that whole wait.
            pollResponses(googleAccount)

            _syncProgress.value = SyncProgress(SyncPhase.DOWNLOADING_METADATA, "Downloading Calibre metadata...")
            val dbFile = File(context.filesDir, "metadata.db")
            val result = syncMetadataDb(googleAccount, dbFile)

            if (result is NetworkResult.Success) {
                _syncProgress.value = SyncProgress(SyncPhase.VERIFYING_TRANSFERS, "Verifying completed transfers...")
                verifyCompletedTransfers(dbFile, googleAccount) { current, total ->
                    _syncProgress.value = SyncProgress(
                        SyncPhase.VERIFYING_TRANSFERS, "Verifying completed transfers ($current/$total)...", current, total,
                    )
                }
            }

            _syncProgress.value = SyncProgress(SyncPhase.CHECKING_HEARTBEAT, "Checking daemon status...")
            pollHeartbeat(googleAccount)

            result
        } finally {
            _syncProgress.value = null
        }
    }

    // (filesDone, totalFiles) while a "send to Calibre" pack is being resolved/dispatched,
    // before its transfer row exists. Lives here (not in FilesViewModel) so TransferPrepareService
    // can observe it and keep the process foregrounded — otherwise Android may kill the work
    // outright once the screen locks, silently losing it before it's ever persisted.
    private val _prepareProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val prepareProgress = _prepareProgress.asStateFlow()

    fun updatePrepareProgress(progress: Pair<Int, Int>?) {
        _prepareProgress.value = progress
    }

    // Counts in-flight "send to Calibre" pack operations (resolving/uploading files before
    // the transfer row exists), so the UI can show an animation during that otherwise-silent gap.
    // Lives here (not in FilesViewModel) for the same reason as prepareProgress above — the
    // dispatch coroutine itself now runs on an app-scoped CoroutineScope so it survives
    // navigating away from the Files screen that started it, and this counter needs to be
    // visible to whichever FilesViewModel instance is current when it completes, not just the
    // one that started it.
    private val _pendingTransferPreparations = MutableStateFlow(0)
    val pendingTransferPreparations = _pendingTransferPreparations.asStateFlow()

    // Overrides the generic "Preparing files for Calibre…" banner text (FilesScreen.kt) for
    // whichever flow is currently driving pendingTransferPreparations — e.g. sendBatchToCalibre
    // sets this to a "Sending…" label for its dispatch loop, since by then most items were
    // already resolved via prefetchBatchLocalPaths and "Preparing" would misleadingly suggest
    // resolution is starting over from scratch.
    private val _transferPreparationLabel = MutableStateFlow("Preparing files for Calibre…")
    val transferPreparationLabel = _transferPreparationLabel.asStateFlow()

    fun setTransferPreparationLabel(label: String) {
        _transferPreparationLabel.value = label
    }

    /** Returns true when this call transitioned the count from 0 to 1 (caller should foreground
     *  the process); see [decrementPendingTransferPreparations] for the matching teardown. */
    fun incrementPendingTransferPreparations(): Boolean {
        var wasIdle = false
        _pendingTransferPreparations.update { current ->
            wasIdle = current == 0
            current + 1
        }
        return wasIdle
    }

    /** Returns true when this call transitioned the count back to 0 (caller should stop
     *  foregrounding the process). */
    fun decrementPendingTransferPreparations(): Boolean {
        var isNowIdle = false
        _pendingTransferPreparations.update { current ->
            val next = (current - 1).coerceAtLeast(0)
            isNowIdle = next == 0
            next
        }
        return isNowIdle
    }

    fun markAssemblyAppendPending(transferId: Long) {
        _pendingAssemblyAppends.value = _pendingAssemblyAppends.value + transferId
    }

    fun clearAssemblyAppendPending(transferId: Long) {
        _pendingAssemblyAppends.value = _pendingAssemblyAppends.value - transferId
    }

    // Tracks the last time updateUploadProgress was called with a non-null value.
    // Used by the orphan detector to catch uploads stuck in a retry loop (where the
    // progress key IS present but no bytes have flowed for several minutes).
    private val _uploadProgressTimestamp = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val uploadProgressTimestamp = _uploadProgressTimestamp.asStateFlow()

    fun updateUploadProgress(transferId: Long, text: String?) {
        if (text == null) {
            _uploadProgress.value = _uploadProgress.value - transferId
            _uploadProgressTimestamp.value = _uploadProgressTimestamp.value - transferId
        } else {
            _uploadProgress.value = _uploadProgress.value + (transferId to text)
            _uploadProgressTimestamp.value = _uploadProgressTimestamp.value + (transferId to System.currentTimeMillis())
        }
    }

    private val json = Json { 
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    fun getTransfers(): Flow<List<CalibreTransferEntity>> = calibreTransferDao.getAllTransfers()

    /** Finds a still-in-flight transfer (not COMPLETED/FAILED) that already covers this file,
     *  whether as a single-file transfer or as one file within a batch/assembly. Used to warn
     *  before placing a duplicate request for a file that's already queued.
     *
     *  Callers must pass [fileId] as `PutioFile.syncedFileId` (not `.id`) for synced files —
     *  that's the original put.io ID embedded in the stub filename, which is what sendToCalibre
     *  actually records as the transfer's putioFileId. `.id` is the stub's own current put.io
     *  ID (a distinct object from the original file), so comparing against it would false-negative.
     *  [fileName] is matched too as a belt-and-suspenders fallback (it's what's stored as the
     *  transfer's fileName and doesn't depend on getting the right ID field at all). */
    suspend fun findPendingTransfer(fileId: Long, fileName: String): CalibreTransferEntity? =
        calibreTransferDao.findPendingTransfer(fileId, fileName)

    // CONTRACT: UPDATE_COMMENTS
    suspend fun sendUpdateCommentsRequest(
        title: String,
        author: String,
        calibreBookId: Long,
        comments: String?,
        tags: String?,
        googleAccount: String,
        calibreBookUuid: String? = null,
        addToChain: Boolean = false,
    ) {
        // We use a fake putio_file_id for comments update as it doesn't involve a file.
        // CONTRACT: negative sign marks it as a fileless/book-level request — the daemon
        // serializes these one at a time (oldest-timestamp-first) instead of racing them
        // against each other, so a quick "fix" sent right after a mistake always lands last.
        val putioFileId = -System.currentTimeMillis()
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "UPDATE_COMMENTS",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_id = calibreBookId,
            calibre_book_uuid = calibreBookUuid,
            comments = comments,
            tags = tags,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        dispatchOrStage(googleAccount, "req_comments_$putioFileId.json", jsonStr, addToChain) { status, gDriveId, errorMessage, chainPosition ->
            CalibreTransferEntity(
                putioFileId = putioFileId,
                fileName = "Update comments for $title",
                title = "Comments for $title",
                author = author,
                status = status,
                addedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                allPutioFileIds = putioFileId.toString(),
                gdriveRequestId = gDriveId,
                errorMessage = errorMessage,
                isTempUpload = true,
                hasPutioFile = false,
                calibreBookUuid = calibreBookUuid,
                lastRequestPayload = jsonStr,
                chainPosition = chainPosition,
            )
        }
    }

    // CONTRACT: UPDATE_COMMENTS (edit_metadata variant — sends all non-null fields together)
    suspend fun sendEditMetadataRequest(pending: PendingEditMetadata, googleAccount: String) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()

        @Serializable
        data class EditMetadataRequest(
            val action: String = "UPDATE_COMMENTS",
            val putio_file_id: Long,
            val calibre_book_uuid: String,
            val title: String? = null,
            val author: String? = null,
            val comments: String? = null,
            val tags: String? = null,
            val page_count: Int? = null,
            val items: List<CalibreBatchItem> = emptyList(),
            val app_id: String? = null,
        )

        val request = EditMetadataRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = pending.uuid,
            title = pending.title,
            author = pending.author,
            comments = pending.comments,
            tags = pending.tags,
            page_count = pending.pageCount,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_editmeta_$putioFileId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Edit metadata",
            title = pending.title ?: "",
            author = pending.author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            isTempUpload = true,
            hasPutioFile = false,
            calibreBookUuid = pending.uuid,
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: BATCH_ADD_TAGS
    suspend fun sendBatchAddTagsRequest(
        uuids: List<String>,
        tags: String,
        googleAccount: String,
        addToChain: Boolean = false,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = BatchAddTagsRequest(
            putio_file_id = putioFileId,
            calibre_book_uuids = uuids,
            tags = tags,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        dispatchOrStage(googleAccount, "req_batch_tags_$putioFileId.json", jsonStr, addToChain) { status, gDriveId, errorMessage, chainPosition ->
            CalibreTransferEntity(
                putioFileId = putioFileId,
                fileName = "Batch add tags to ${uuids.size} books",
                title = "Batch tag: $tags",
                author = "",
                status = status,
                addedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                allPutioFileIds = putioFileId.toString(),
                gdriveRequestId = gDriveId,
                errorMessage = errorMessage,
                isTempUpload = true,
                hasPutioFile = false,
                lastRequestPayload = jsonStr,
                chainPosition = chainPosition,
            )
        }
    }

    // CONTRACT: PRIORITY_PUTIO_SYNC
    suspend fun sendPrioritySyncRequest(file: PutioFile, googleAccount: String): Boolean {
        val appId = settingsRepository.getOrCreateAppId()
        val request = PrioritySyncRequest(putio_file_id = file.id, app_id = appId)
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_priority_${file.id}.json", jsonStr)
        return gDriveId != null
    }

    suspend fun sendGlobalStatusProbe(googleAccount: String): Boolean {
        val appId = settingsRepository.getOrCreateAppId()
        val request = GlobalStatusProbeRequest(app_id = appId)
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_global_status.json", jsonStr)
        return gDriveId != null
    }

    suspend fun addTransfer(
        putioFileId: Long,
        fileName: String,
        title: String,
        author: String,
        googleAccount: String,
        downloadUrl: String?,
        archiveMode: String? = null,
        isTempUpload: Boolean = false,
        sourceLocalUri: String? = null,
        assembleBook: Boolean = false,
        calibreBookUuid: String? = null,
        isUploading: Boolean = false,
        localUrisJson: String? = null,
        useLocal: Boolean = false,
        smbPath: String? = null,
        archiveEntry: String? = null,
        localPath: String? = null,   // CONTRACT: stub convention — relative path within the local mirror repo
        isProtected: Boolean = false,
        ignoreCover: Boolean = false,
        convertToPdf: Boolean = false,
        tags: String? = null,
        comments: String? = null,
        // Lets a concurrent batch caller (see FilesViewModel.sendBatchToCalibre) stamp a
        // deterministic, list-order-preserving value instead of real dispatch time — when
        // several items are sent in parallel, whichever coroutine happens to reach this line
        // first would otherwise win the "most recent" slot in the addedAt-sorted transfer list,
        // regardless of the order the user actually queued them in.
        addedAt: Long? = null,
        priority: Boolean = false,
        addToChain: Boolean = false,
    ) {
        val initialItem = CalibreBatchItem(
            type = when {
                archiveEntry != null -> "ARCHIVE_ENTRY"
                archiveMode != null -> "ARCHIVE"
                else -> "SINGLE"
            },
            putio_file_id = putioFileId,
            fileName = fileName,
            download_url = downloadUrl,
            archiveMode = archiveMode,
            use_local = if (useLocal) true else null,
            local_path = localPath,
            smb_path = smbPath,
            archive_entry = archiveEntry,
            protected = if (isProtected) true else null,
            convert_to_pdf = if (convertToPdf) true else null,
        )
        // CONTRACT: CHAIN — staged chain members skip dispatch just like ASSEMBLED/UPLOADING
        // placeholders already do, but (unlike those) need their full request payload saved
        // immediately: placeChain() reads every chain member's lastRequestPayload verbatim, with
        // no reconstruct-from-batchData fallback the way retryTransfer has for legacy rows.
        val chainPosition = if (addToChain) (calibreTransferDao.maxChainPosition() ?: -1) + 1 else null
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = listOf(initialItem),
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
            tags = tags?.ifBlank { null },
            comments = comments?.ifBlank { null },
            keep_cover = if (isProtected && ignoreCover) true else null,
        )
        val jsonStr = json.encodeToString(request)
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = when {
                addToChain -> CalibreTransferStatus.CHAINED
                assembleBook -> CalibreTransferStatus.ASSEMBLED
                isUploading -> CalibreTransferStatus.UPLOADING
                else -> CalibreTransferStatus.PENDING
            },
            addedAt = addedAt ?: System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            isTempUpload = isTempUpload,
            sourceLocalUri = sourceLocalUri,
            batchData = json.encodeToString(listOf(initialItem)),
            calibreBookUuid = calibreBookUuid,
            localUrisJson = localUrisJson,
            tags = tags?.ifBlank { null },
            comments = comments?.ifBlank { null },
            priority = priority,
            chainPosition = chainPosition,
            lastRequestPayload = if (addToChain) jsonStr else null,
            ignoreCover = ignoreCover,
        )
        calibreTransferDao.insertTransfer(transfer)

        // useLocal without local_path: park as PENDING so caller can resolve path before dispatching
        // useLocal/smbPath means we can dispatch immediately without a download URL
        if (addToChain || assembleBook || isUploading || (downloadUrl == null && !useLocal && smbPath == null) || (useLocal && localPath == null)) return

        // Immediately try to upload request
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$putioFileId.json", jsonStr, isPriority = priority)

        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr
            ))
        } else {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.FAILED,
                errorMessage = "Failed to upload to GDrive",
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr
            ))
        }
    }

    // Called after addTransfer(useLocal=true, localPath=null) once the stub path is resolved.
    // For ASSEMBLED transfers just persists the path; for PENDING transfers dispatches to GDrive.
    suspend fun resolveLocalPathAndDispatch(fileId: Long, localPath: String?, googleAccount: String) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        if (transfer.status != CalibreTransferStatus.PENDING && transfer.status != CalibreTransferStatus.ASSEMBLED) return

        val items = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        } ?: return
        val updatedItems = items.map { item ->
            if (item.use_local == true && item.local_path == null) item.copy(local_path = localPath) else item
        }

        if (transfer.status == CalibreTransferStatus.ASSEMBLED) {
            calibreTransferDao.updateTransfer(transfer.copy(
                batchData = json.encodeToString(updatedItems),
                lastUpdatedAt = System.currentTimeMillis(),
            ))
            return
        }

        // Path resolution can fail transiently (e.g. no connectivity) and return null.
        // Never dispatch a use_local item without its path — the daemon has no way to
        // resolve it (the legacy sync-index fallback is dead) and would fail permanently
        // after 15 retries. Stay PENDING so a later resolve attempt can complete this.
        if (updatedItems.any { it.use_local == true && it.local_path == null }) {
            calibreTransferDao.updateTransfer(transfer.copy(
                batchData = json.encodeToString(updatedItems),
                lastUpdatedAt = System.currentTimeMillis(),
            ))
            return
        }

        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            putio_file_id = transfer.putioFileId,
            title = transfer.title,
            author = transfer.author,
            items = updatedItems,
            calibre_book_uuid = transfer.calibreBookUuid,
            app_id = appId,
            tags = transfer.tags,
            comments = transfer.comments,
            keep_cover = if (transfer.ignoreCover) true else null,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_${transfer.putioFileId}.json", jsonStr, isPriority = transfer.priority)
        calibreTransferDao.updateTransfer(transfer.copy(
            batchData = json.encodeToString(updatedItems),
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastUpdatedAt = System.currentTimeMillis(),
            lastRequestPayload = jsonStr,
        ))
    }

    suspend fun updateTransferAfterUpload(fileId: Long, newPutioFileId: Long, downloadUrl: String, googleAccount: String) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return

        // Update batch data with new ID and URL
        val items = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: emptyList()

        val updatedItems = items.map { it.copy(putio_file_id = newPutioFileId, download_url = downloadUrl) }
        val updatedBatchData = json.encodeToString(updatedItems)

        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            putio_file_id = newPutioFileId,
            title = transfer.title,
            author = transfer.author,
            items = updatedItems,
            calibre_book_uuid = transfer.calibreBookUuid,
            app_id = appId,
            tags = transfer.tags,
            comments = transfer.comments,
            keep_cover = if (transfer.ignoreCover) true else null,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$newPutioFileId.json", jsonStr, isPriority = transfer.priority)

        calibreTransferDao.deleteTransfer(fileId) // Remove temp placeholder
        calibreTransferDao.insertTransfer(transfer.copy(
            putioFileId = newPutioFileId,
            allPutioFileIds = newPutioFileId.toString(),
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastUpdatedAt = System.currentTimeMillis(),
            lastRequestPayload = jsonStr,
            batchData = updatedBatchData
        ))
    }

    // CONTRACT: ADD_BOOK_BATCH — merge framework. Generic transfer-submission for any
    // merge/pack item type built on MergePackJob (see CONTRACTS.md "Merge framework").
    // Pass either `files` (flat) or `groups` (chaptered), not both.
    suspend fun addMergeTransfer(
        type: String,
        fileName: String,
        files: List<Pair<PutioFile, AudiobookFile>>? = null,
        groups: List<Pair<String, List<Pair<PutioFile, AudiobookFile>>>>? = null,
        title: String,
        author: String,
        googleAccount: String,
        assembleBook: Boolean = false,
        calibreBookUuid: String? = null,
        isUploading: Boolean = false,
        localUrisJson: String? = null,
        tags: String? = null,
        isProtected: Boolean = false,
        ignoreCover: Boolean = false,
        priority: Boolean = false,
        addToChain: Boolean = false,
        imageQuality: Int? = null,
        comments: String? = null,
        convertToPdf: Boolean = false,
    ) {
        val allPairs = files ?: groups?.flatMap { (_, groupFiles) -> groupFiles }
            ?: error("addMergeTransfer requires either files or groups")
        val primaryFileId = allPairs.first().first.id

        val initialItem = CalibreBatchItem(
            type = type,
            putio_file_id = primaryFileId,
            fileName = fileName,
            // Tag every file with sourceBatch = 1 so the daemon (and future appends) can
            // detect multi-source assemblies. Groups path is excluded — it uses the groups
            // mechanism for chapter structure, not sourceBatch.
            files = files?.map { (_, f) -> f.copy(sourceBatch = 1) },
            groups = groups?.map { (label, groupFiles) -> PackGroup(label, groupFiles.map { (_, f) -> f }) },
            protected = if (isProtected) true else null,
            image_quality = imageQuality,
            convert_to_pdf = if (convertToPdf) true else null,
        )
        // CONTRACT: CHAIN — see addTransfer's identical comment; placeChain() reads
        // lastRequestPayload verbatim for every staged chain member.
        val chainPosition = if (addToChain) (calibreTransferDao.maxChainPosition() ?: -1) + 1 else null
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            putio_file_id = primaryFileId,
            title = title,
            author = author,
            items = listOf(initialItem),
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
            tags = tags?.ifBlank { null },
            comments = comments?.ifBlank { null },
            keep_cover = if (isProtected && ignoreCover) true else null,
        )
        val jsonStr = json.encodeToString(request)
        val transfer = CalibreTransferEntity(
            putioFileId = primaryFileId,
            fileName = fileName,
            title = title,
            author = author,
            status = when {
                addToChain -> CalibreTransferStatus.CHAINED
                assembleBook -> CalibreTransferStatus.ASSEMBLED
                isUploading -> CalibreTransferStatus.UPLOADING
                else -> CalibreTransferStatus.PENDING
            },
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = allPairs.joinToString(",") { (_, f) -> f.putio_file_id.toString() },
            batchData = json.encodeToString(listOf(initialItem)),
            calibreBookUuid = calibreBookUuid,
            localUrisJson = localUrisJson,
            tags = tags?.ifBlank { null },
            comments = comments?.ifBlank { null },
            priority = priority,
            chainPosition = chainPosition,
            lastRequestPayload = if (addToChain) jsonStr else null,
            ignoreCover = ignoreCover,
        )
        calibreTransferDao.insertTransfer(transfer)

        // isUploading: a placeholder row for an on-device-local-file pack — the caller uploads
        // each file afterward and finishes via updateMergeAfterUpload/removeTransfer+re-add.
        // If the app is killed mid-upload, GlobalSyncViewModel's orphan detector resumes from
        // localUrisJson via restartOrphanedUpload (CONTRACT: works for any item type, since it
        // only reads files/fileName generically from the stored batchData).
        if (addToChain || assembleBook || isUploading || allPairs.any { (_, f) -> f.download_url == null && f.smb_path == null && f.use_local != true }) return

        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_$primaryFileId.json", jsonStr, isPriority = priority)

        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr,
            ))
        } else {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.FAILED,
                errorMessage = "Failed to upload to GDrive",
                lastUpdatedAt = System.currentTimeMillis(),
                lastRequestPayload = jsonStr,
            ))
        }
    }

    // CONTRACT: merge framework. Finishes a multi-file on-device-local-upload placeholder
    // transfer (status=UPLOADING, created via addMergeTransfer's isUploading branch) once
    // every file has a real put.io download_url. The item `type`/`fileName` are read back from
    // the placeholder's own stored batchData rather than a hardcoded type, so this works for
    // any merge engine, not just PACK — restartOrphanedUpload (the app-kill resume path) relies
    // on that genericity too.
    suspend fun updateMergeAfterUpload(tempId: Long, resolvedFiles: List<AudiobookFile>, googleAccount: String) {
        val transfer = calibreTransferDao.getTransferById(tempId) ?: return
        val placeholderItem = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it).firstOrNull() } catch (e: Exception) { null }
        }

        val newPrimaryId = resolvedFiles.first().putio_file_id
        val items = listOf(CalibreBatchItem(
            type = placeholderItem?.type ?: "PACK",
            putio_file_id = newPrimaryId,
            fileName = placeholderItem?.fileName ?: transfer.fileName,
            files = resolvedFiles.map { it.copy(sourceBatch = 1) },
        ))

        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            putio_file_id = newPrimaryId,
            title = transfer.title,
            author = transfer.author,
            items = items,
            calibre_book_uuid = transfer.calibreBookUuid,
            app_id = appId,
            tags = transfer.tags,
            comments = transfer.comments,
            keep_cover = if (transfer.ignoreCover) true else null,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_$newPrimaryId.json", jsonStr, isPriority = transfer.priority)

        calibreTransferDao.deleteTransfer(tempId)
        calibreTransferDao.insertTransfer(transfer.copy(
            putioFileId = newPrimaryId,
            allPutioFileIds = resolvedFiles.joinToString(",") { it.putio_file_id.toString() },
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastUpdatedAt = System.currentTimeMillis(),
            lastRequestPayload = jsonStr,
            batchData = json.encodeToString(items)
        ))
    }

    // CONTRACT: merge framework — pack-shaped item types whose `files`/`groups` hold the
    // homogeneous source files being joined, as opposed to SINGLE/ARCHIVE/ARCHIVE_ENTRY items.
    private val PACK_TYPES = setOf("PACK", "PDF_PACK", "EPUB_PACK", "IMAGE_PDF_PACK", "IMAGE_EPUB_PACK", "IMAGE_CBZ_PACK", "CBR_PDF_PACK", "CBR_CBZ_PACK")
    private val CBZ_TYPES = setOf("IMAGE_CBZ_PACK", "CBR_CBZ_PACK")

    // Only PDF_PACK/EPUB_PACK/TEXT_PDF_PACK can absorb a lone SINGLE item of the matching
    // extension into the pack as its first file: a finished .pdf/.epub is valid raw input for
    // "join PDFs/EPUBs", and TEXT_PDF_PACK's build_output ends in the same PdfPackJob merge as
    // PDF_PACK, so a finished .pdf is just as valid there. IMAGE_PDF_PACK/CBR_PDF_PACK take raw
    // images/archives as input, so a finished SINGLE .pdf isn't valid input for those — no
    // promotion offered for them.
    private val PROMOTABLE_SINGLE_EXTENSION = mapOf("PDF_PACK" to "pdf", "EPUB_PACK" to "epub", "TEXT_PDF_PACK" to "pdf")

    private fun CalibreBatchItem.sourceFileNames(): List<String> = when {
        type in PACK_TYPES && files != null -> files.map { it.fileName }
        type in PACK_TYPES && groups != null -> groups.flatMap { g -> g.files.map { it.fileName } }
        else -> listOf(fileName)
    }

    private fun CalibreBatchItem.outputExtension(): String =
        fileName.substringAfterLast('.', "").lowercase()

    /**
     * Finds the item within [transfer]'s batchData that a new [payloadType] pack should fold
     * into: either an existing item of the same type, or — for PDF_PACK/EPUB_PACK only — a lone
     * SINGLE item of the matching output extension that can be promoted into the pack's first
     * file. Returns null if nothing compatible exists.
     */
    fun compatibleAssemblyItem(transfer: CalibreTransferEntity, payloadType: String): CalibreBatchItem? {
        val items = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: return null
        items.firstOrNull { it.type == payloadType }?.let { return it }
        // Cross-CBZ compatibility: IMAGE_CBZ_PACK and CBR_CBZ_PACK can be merged together.
        if (payloadType in CBZ_TYPES) {
            items.firstOrNull { it.type in CBZ_TYPES }?.let { return it }
        }
        val ext = PROMOTABLE_SINGLE_EXTENSION[payloadType] ?: return null
        return items.firstOrNull { it.type == "SINGLE" && it.outputExtension() == ext }
    }

    /**
     * Folds [newItem]'s files into the matching item found by [compatibleAssemblyItem] (extending
     * an existing pack, or promoting a lone matching SINGLE into a new pack) instead of appending
     * [newItem] as a separate item — this is what lets a join-pack be built up across multiple
     * "Assemble into joined X" calls without hitting the daemon's same-output-format dedup, which
     * otherwise silently drops the second of two same-type pack items at dispatch (see the TODO
     * on [appendToAssembly] below). Falls back to [appendToAssembly] when nothing is compatible.
     * Always preserves the assembly's own title/author/tags — those came from [transfer], not
     * from whatever the user typed while building this new batch.
     */
    suspend fun mergeIntoAssemblyItem(
        assemblyFileId: Long,
        newItem: CalibreBatchItem,
        newFileIds: List<Long>,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        overrideUuid: String? = null,
        overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ): Boolean {
        val transfer = calibreTransferDao.getTransferById(assemblyFileId) ?: return false
        val matched = compatibleAssemblyItem(transfer, newItem.type)
            ?: return appendToAssembly(assemblyFileId, newItem, newFileIds, overrideTitle, overrideAuthor, overrideUuid, overrideTags, overrideProtected)

        // v1: only flat (ungrouped) packs can be combined this way.
        if (matched.groups != null || newItem.groups != null) return false

        // If the incoming item targets a different output slot (e.g. "Book.pdf_bkp" vs the
        // existing "Book.pdf"), treat it as a separate item rather than folding files in —
        // otherwise the _bkp fileName is silently discarded and the user gets one big pack
        // instead of two separate format slots.
        if (matched.type == newItem.type && matched.fileName != newItem.fileName)
            return appendToAssembly(assemblyFileId, newItem, newFileIds, overrideTitle, overrideAuthor, overrideUuid, overrideTags, overrideProtected)

        val currentItems = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: emptyList()

        // Determine the next batch index: max existing sourceBatch (null = legacy → 1) + 1.
        val existingFiles = matched.files ?: emptyList()
        val nextBatch = (existingFiles.maxOfOrNull { it.sourceBatch ?: 1 } ?: 1) + 1
        val taggedNewFiles = (newItem.files ?: emptyList()).map { it.copy(sourceBatch = nextBatch) }

        // Collision check: same (sourceBatch, fileName) pair already present. Since nextBatch
        // is always > any existing batch, this guards against re-adding the same file in a
        // retry scenario where the batch counter somehow stalls.
        val existingKeys = existingFiles.map { (it.sourceBatch ?: 1) to it.fileName }.toSet()
        if (taggedNewFiles.any { (nextBatch to it.fileName) in existingKeys }) return false

        val mergedItem = when {
            matched.type == newItem.type ->
                matched.copy(files = existingFiles + taggedNewFiles)
            matched.type in CBZ_TYPES && newItem.type in CBZ_TYPES ->
                // Cross-CBZ merge: unify under CBR_CBZ_PACK which handles both plain images
                // and CBR archives in the same file list.
                matched.copy(type = "CBR_CBZ_PACK", files = existingFiles + taggedNewFiles)
            else -> {
                // Promotion: matched is a lone SINGLE of the matching extension — fold it in
                // as the pack's first file.
                val promotedFile = AudiobookFile(
                    putio_file_id = matched.putio_file_id,
                    fileName = matched.fileName,
                    download_url = matched.download_url,
                    smb_path = matched.smb_path,
                    use_local = matched.use_local,
                    local_path = matched.local_path,
                    sourceBatch = 1,
                )
                newItem.copy(files = listOf(promotedFile) + taggedNewFiles)
            }
        }

        // Inherit encryption flag from the matched item unless explicitly overridden — without
        // this, overrideProtected was silently discarded on this merge path (only appendToAssembly
        // applied it), so re-checking/unchecking "protect" while adding a batch to an existing
        // pack item had no effect on the dispatched request.
        val inheritedProtected = overrideProtected ?: matched.protected
        val mergedItemWithProtected = mergedItem.copy(protected = inheritedProtected)

        // If overriding, keep the whole assembly consistently protected/unprotected, mirroring
        // appendToAssembly's behavior — not just the item being merged into.
        val updatedItems = currentItems.map { existing ->
            when {
                existing == matched -> mergedItemWithProtected
                overrideProtected != null -> existing.copy(protected = overrideProtected)
                else -> existing
            }
        }
        val updatedIds = (transfer.parsedFileIds() + newFileIds).distinct()

        calibreTransferDao.updateTransfer(transfer.copy(
            title = overrideTitle ?: transfer.title,
            author = overrideAuthor ?: transfer.author,
            calibreBookUuid = if (overrideTitle != null) overrideUuid else transfer.calibreBookUuid,
            tags = if (overrideTitle != null) overrideTags?.takeIf { it.isNotBlank() } else transfer.tags,
            batchData = json.encodeToString(updatedItems),
            allPutioFileIds = updatedIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = null,
        ))
        return true
    }

    suspend fun appendToAssembly(
        assemblyFileId: Long,
        newItem: CalibreBatchItem,
        newFileIds: List<Long>,
        overrideTitle: String? = null,
        overrideAuthor: String? = null,
        overrideUuid: String? = null,
        overrideTags: String? = null,
        overrideProtected: Boolean? = null,
    ): Boolean {
        val transfer = calibreTransferDao.getTransferById(assemblyFileId) ?: return false
        // If overriding protected, update all existing items to match before appending.
        val baseItems = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (e: Exception) { null }
        } ?: emptyList()
        val currentItems = if (overrideProtected != null) {
            baseItems.map { it.copy(protected = overrideProtected) }
        } else {
            baseItems
        }

        // Inherit encryption flag from first item (post-override) so all formats are consistently protected.
        val inheritedProtected = overrideProtected ?: currentItems.firstOrNull()?.protected

        // Reject if the incoming SINGLE item's format slot is already taken.
        // The UI (AssemblyAppendSheet) prevents this by disabling the button unless the user
        // explicitly toggles alt version — at which point the ViewModel applies _bkp before
        // calling here. This check is a safety net so duplicates can never sneak through.
        var effectiveItem = newItem
        if (newItem.type == "SINGLE") {
            val ext = newItem.fileName.substringAfterLast('.', "").uppercase()
            if (ext.isNotEmpty()) {
                val existingFormats = currentItems
                    .filter { it.type == "SINGLE" }
                    .map { it.fileName.substringAfterLast('.', "").uppercase() }
                    .toSet()
                if (ext in existingFormats) return false
            }
        }

        val existingFileNames = currentItems.flatMap { item ->
            if (item.type == "PACK" || item.type == "PDF_PACK") item.files?.map { it.fileName } ?: listOf(item.fileName)
            else listOf(item.fileName)
        }.toSet()
        val incomingFileNames = if (effectiveItem.type == "PACK" || effectiveItem.type == "PDF_PACK")
            effectiveItem.files?.map { it.fileName } ?: listOf(effectiveItem.fileName)
        else listOf(effectiveItem.fileName)
        if (incomingFileNames.any { it in existingFileNames }) return false

        if (inheritedProtected != null) effectiveItem = effectiveItem.copy(protected = inheritedProtected)
        val updatedItems = currentItems + effectiveItem
        val updatedIds = (transfer.parsedFileIds() + newFileIds).distinct()

        calibreTransferDao.updateTransfer(transfer.copy(
            title = overrideTitle ?: transfer.title,
            author = overrideAuthor ?: transfer.author,
            calibreBookUuid = if (overrideTitle != null) overrideUuid else transfer.calibreBookUuid,
            tags = if (overrideTitle != null) overrideTags?.takeIf { it.isNotBlank() } else transfer.tags,
            batchData = json.encodeToString(updatedItems),
            allPutioFileIds = updatedIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = null,
        ))
        return true
    }

    suspend fun updateAssemblyMetadata(
        fileId: Long,
        title: String,
        author: String,
        tags: String?,
        items: List<CalibreBatchItem>,
        ignoreCover: Boolean = false,
        comments: String? = null,
    ) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        val newAllIds = items.flatMap { item ->
            val ids = mutableListOf(item.putio_file_id)
            item.files?.mapTo(ids) { it.putio_file_id }
            item.groups?.forEach { g -> g.files.mapTo(ids) { it.putio_file_id } }
            ids
        }.distinct()
        calibreTransferDao.updateTransfer(transfer.copy(
            title = title,
            author = author,
            tags = tags?.takeIf { it.isNotBlank() },
            comments = comments?.takeIf { it.isNotBlank() },
            batchData = json.encodeToString(items),
            allPutioFileIds = newAllIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis(),
            ignoreCover = ignoreCover,
        ))
    }

    suspend fun setTransferErrorMessage(fileId: Long, message: String?) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        calibreTransferDao.updateTransfer(transfer.copy(errorMessage = message))
    }

    suspend fun getPendingAssemblies(): List<CalibreTransferEntity> {
        return calibreTransferDao.getAllTransfers().first().filter { 
            it.status == CalibreTransferStatus.ASSEMBLED 
        }
    }

    suspend fun syncMetadataDb(googleAccount: String, destination: File): NetworkResult<Unit> {
        val success = daemonTransport.downloadMetadataDb(googleAccount, destination)
        if (success) {
            daemonTransport.getLibraryVersion(googleAccount)?.let { timestamp ->
                settingsRepository.saveLastSyncTimestamp(timestamp)
                settingsRepository.saveLibraryHasUpdates(false)
            }
        }
        return if (success) NetworkResult.Success(Unit) else NetworkResult.Error("Download failed")
    }

    suspend fun pollLibraryUpdates(googleAccount: String) {
        val remoteTimestamp = daemonTransport.getLibraryVersion(googleAccount) ?: return
        val localTimestamp = settingsRepository.lastSyncTimestampFlow.first()
        if (localTimestamp == 0L) {
            settingsRepository.saveLastSyncTimestamp(remoteTimestamp)
        } else if (remoteTimestamp != localTimestamp) {
            settingsRepository.saveLibraryHasUpdates(true)
        }
    }

    suspend fun pollHeartbeat(googleAccount: String) {
        val heartbeat = daemonTransport.getHeartbeat(googleAccount) ?: return
        _daemonStatus.value = heartbeat.status
        settingsRepository.saveDaemonStatus(heartbeat.status)
        settingsRepository.saveHistoryFileId(heartbeat.historyFileId)
    }

    // CONTRACT: REGISTER_TRANSFER_HISTORY
    /** Returns true once the request has been durably handed off (Drive upload succeeded, so the
     *  daemon will pick it up even if it's offline right now) — false if it was never persisted
     *  anywhere and the caller should treat the registration as having not happened. */
    suspend fun registerTransferHistory(
        putioTransferId: Long,
        infoHash: String,
        label: String,
        labelUpdatedAt: Long? = null,
        putioName: String?,
        magnetUri: String?,
        putioId: Long?,
        status: String,
        googleAccount: String,
    ): Boolean {
        if (googleAccount.isBlank()) return false
        val appId = settingsRepository.getOrCreateAppId()
        val request = RegisterHistoryRequest(
            putio_file_id = putioTransferId,
            info_hash = infoHash,
            label = label,
            label_updated_at = labelUpdatedAt,
            putio_name = putioName,
            magnet_uri = magnetUri,
            putio_id = putioId,
            status = status,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val driveId = daemonTransport.submitRequest(googleAccount, "req_hist_$putioTransferId.json", jsonStr)
        // Response is silently acknowledged by pollResponses — no DB tracking needed for history.
        // The non-null check here is the only confirmation that the write actually landed somewhere durable.
        return driveId != null
    }

    // CONTRACT: progress reporting — this reports into _syncProgress itself (rather than taking
    // caller-supplied callbacks) because pollResponsesMutex makes it single-flight: whichever
    // caller (GlobalSyncViewModel's background loop, or a manual performFullSync) is inside this
    // function at any moment is the ONLY one actually doing work — the other is just parked on
    // the mutex. Progress has to belong to whoever's really running, not to whichever caller
    // happened to ask for it, or a manual sync blocked behind a big background-loop backlog would
    // show a static, seemingly-frozen message for the entire wait instead of the real numbers.
    suspend fun pollResponses(googleAccount: String): Unit = pollResponsesMutex.withLock {
        val myAppId = settingsRepository.getOrCreateAppId()
        _syncProgress.value = SyncProgress(SyncPhase.PULLING_RESPONSES, "Checking for daemon responses...")
        val envelopes = daemonTransport.pollResponses(
            googleAccount, myAppId,
            onCleanupProgress = { current, total ->
                _syncProgress.value = SyncProgress(
                    SyncPhase.PULLING_RESPONSES,
                    "Cleaning up $total already-processed response(s) ($current/$total)...", current, total,
                )
            },
            onFetchProgress = { current, total ->
                _syncProgress.value = SyncProgress(
                    SyncPhase.PULLING_RESPONSES,
                    "Fetching $total new response(s) ($current/$total)...", current, total,
                )
            },
            onListProgress = { current ->
                _syncProgress.value = SyncProgress(
                    SyncPhase.PULLING_RESPONSES, "Listing daemon responses ($current found so far)...", current,
                )
            },
        )
        if (envelopes.isNotEmpty()) {
            android.util.Log.d("CalibreRepository", "pollResponses: fetched ${envelopes.size} envelope(s) for app_id=$myAppId")
        }
        envelopes.forEachIndexed { index, envelope ->
            try {
                val response = json.decodeFromString<CalibreResponse>(envelope.content)

                if (response.action == "GLOBAL_STATUS_PROBE") {
                    _daemonStatus.value = response.daemon_status
                    settingsRepository.saveDaemonStatus(response.daemon_status)
                } else if (response.putio_file_id != null) {
                    val transfer = calibreTransferDao.getTransferById(response.putio_file_id)
                    if (transfer == null) {
                        // No local record for this putio_file_id (deleted locally, from a
                        // reinstalled/wiped DB, or a stale duplicate response for a request
                        // that already completed via a different response). Nothing to apply
                        // it to — still falls through to acknowledgeResponse() below so it gets
                        // deleted from Drive instead of lingering forever.
                        android.util.Log.d("CalibreRepository", "pollResponses: orphaned response for putio_file_id=${response.putio_file_id} (no local transfer) — discarding")
                    }
                    if (transfer != null) {
                        val newStatus = when (response.status.uppercase()) {
                            "PROCESSING" -> CalibreTransferStatus.PROCESSING
                            "COMPLETED" -> CalibreTransferStatus.COMPLETED
                            "FAILED" -> CalibreTransferStatus.FAILED
                            else -> transfer.status
                        }

                        val isNewerStatus = newStatus.ordinal > transfer.status.ordinal
                        val isSameStatusFailure = newStatus == CalibreTransferStatus.FAILED && transfer.status == CalibreTransferStatus.FAILED
                        val isProcessingUpdate = newStatus == CalibreTransferStatus.PROCESSING && transfer.status == CalibreTransferStatus.PROCESSING

                        if (isNewerStatus || isSameStatusFailure || isProcessingUpdate) {
                            // These action types are self-verifying: the daemon's COMPLETED
                            // response is the only confirmation needed (no library state change
                            // to cross-check). Mark requests just confirm feasibility; cancel is
                            // a client-side acknowledgement; Plex verifies files physically.
                            val selfVerifyingActions = setOf(
                                "MARK_BOOK_FOR_DELETION", "MARK_FORMATS_FOR_DELETION", "CANCEL_DELETION"
                            )
                            val libraryVerified = newStatus == CalibreTransferStatus.COMPLETED &&
                                (transfer.transferType == "PLEX" || transfer.transferType == "PLEXAMP" || response.action in selfVerifyingActions)
                            val resolvedUuid = if (newStatus == CalibreTransferStatus.COMPLETED) response.calibre_book_uuid ?: transfer.calibreBookUuid else null
                            calibreTransferDao.updateTransfer(transfer.copy(
                                status = newStatus,
                                errorMessage = response.error,
                                calibreBookUuid = if (newStatus == CalibreTransferStatus.COMPLETED && response.calibre_book_uuid != null) response.calibre_book_uuid else transfer.calibreBookUuid,
                                calibreBookId = if (newStatus == CalibreTransferStatus.COMPLETED && response.calibre_book_id != null) response.calibre_book_id else transfer.calibreBookId,
                                warnings = if (newStatus == CalibreTransferStatus.COMPLETED) response.warnings?.joinToString("\n")?.takeIf { it.isNotBlank() } else transfer.warnings,
                                durationSeconds = if (newStatus == CalibreTransferStatus.COMPLETED && response.duration_seconds != null) response.duration_seconds else transfer.durationSeconds,
                                libraryVerified = libraryVerified,
                                lastUpdatedAt = System.currentTimeMillis(),
                                // Consumed below via the pre-update `transfer` snapshot, then cleared here.
                                pendingCoverPutioFileId = if (newStatus == CalibreTransferStatus.COMPLETED) null else transfer.pendingCoverPutioFileId,
                                pendingCoverDownloadUrl = if (newStatus == CalibreTransferStatus.COMPLETED) null else transfer.pendingCoverDownloadUrl,
                                pendingCoverFileName = if (newStatus == CalibreTransferStatus.COMPLETED) null else transfer.pendingCoverFileName,
                            ))

                            if (newStatus == CalibreTransferStatus.COMPLETED && transfer.isTempUpload && transfer.hasPutioFile) {
                                val token = secureStorage.authTokenFlow.value
                                if (token.isNotBlank()) {
                                    deleteFileFromPutio(token, transfer.putioFileId)
                                }
                            }

                            // A cover was staged from the clipboard while this was still an
                            // unsent ASSEMBLED/CHAINED request (attachClipboardCoverToAssembly) —
                            // now that the book's real calibre_book_uuid is known, apply it via
                            // the same REPLACE_COVER path calibreAnywhere's clipboard-cover flow uses.
                            if (newStatus == CalibreTransferStatus.COMPLETED && resolvedUuid != null && transfer.pendingCoverDownloadUrl != null && transfer.pendingCoverPutioFileId != null) {
                                sendReplaceCoverRequest(
                                    putioFileId = transfer.pendingCoverPutioFileId,
                                    fileName = transfer.pendingCoverFileName ?: "cover.jpg",
                                    title = transfer.title,
                                    author = transfer.author,
                                    calibreBookId = (response.calibre_book_id ?: transfer.calibreBookId ?: 0).toLong(),
                                    googleAccount = googleAccount,
                                    downloadUrl = transfer.pendingCoverDownloadUrl,
                                    calibreBookUuid = resolvedUuid,
                                    isPriority = transfer.priority,
                                )
                            }

                            // "missing formats" is just as auto-recoverable as "not found": it's
                            // what a probe reports when an earlier attempt got interrupted after
                            // creating the book but before adding the format (e.g. daemon restart
                            // mid-merge). The daemon's existing-format dedup check makes a re-send
                            // idempotent, so retry it the same way as "not found" rather than
                            // leaving it FAILED forever with only probes (which can never re-do
                            // the actual merge/add-format work) able to touch it.
                            val isAutoRecoverable = response.error?.let {
                                it.contains("not found", ignoreCase = true) || it.contains("missing formats", ignoreCase = true)
                            } == true
                            // JOIN shares FUSION's exclusion here for the same reason: both create a
                            // brand-new book id per attempt, so a naive auto-retry on "not found" would
                            // produce a second duplicate book rather than fixing the original.
                            // MOVE_FORMAT is excluded for a different reason: its own daemon-side "not
                            // found" errors mean the source or destination UUID genuinely doesn't
                            // resolve to a book (deleted, never existed) — a condition auto-retry can
                            // never fix, unlike the interrupted-mid-merge case this check exists for.
                            if (newStatus == CalibreTransferStatus.FAILED && transfer.transferType != "FUSION" && transfer.transferType != "JOIN" && transfer.transferType != "MOVE_FORMAT" && isAutoRecoverable) {
                                if (transfer.retryCount < 3) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val delayMs = Random.nextLong(2000, 60000)
                                        delay(delayMs)
                                        retryTransfer(transfer.putioFileId, googleAccount)
                                    }
                                }
                            }
                        } else {
                            // No real status transition. If this was a manual "Check & refresh"
                            // probe on an already-completed transfer (CalibreTransferItem.kt),
                            // record the result: a COMPLETED probe means the daemon re-verified
                            // the book/formats and refreshed assets.db for it, so bump the
                            // displayed check count; a FAILED probe means it found a real
                            // discrepancy, surfaced via errorMessage without touching status
                            // (the original add did succeed — only this later check disagrees).
                            // probesAwaitingResponse.remove(...) returns true only for the FIRST
                            // response matched to this probe attempt — SmartDaemonTransport can
                            // deliver the same logical response twice (once via LAN, once via
                            // Drive) by design, so without this a single probe could be counted
                            // more than once.
                            val isFirstProbeResponse = response.is_probe == true && probesAwaitingResponse.remove(transfer.putioFileId)
                            val isCompletedRecheck = isFirstProbeResponse && transfer.status == CalibreTransferStatus.COMPLETED
                            val isConfirmedByDaemon = isCompletedRecheck && newStatus == CalibreTransferStatus.COMPLETED
                            calibreTransferDao.updateTransfer(transfer.copy(
                                lastUpdatedAt = System.currentTimeMillis(),
                                probeCount = if (isConfirmedByDaemon) transfer.probeCount + 1 else transfer.probeCount,
                                errorMessage = if (isCompletedRecheck && newStatus == CalibreTransferStatus.FAILED) response.error else transfer.errorMessage,
                                // The daemon checks its live, unsplit metadata.db, so a COMPLETED
                                // recheck is authoritative even when Putz's local (protection-split)
                                // copy couldn't find the book — see verifyCompletedTransfers().
                                libraryVerified = if (isConfirmedByDaemon) true else transfer.libraryVerified,
                            ))
                        }
                    }
                }
                daemonTransport.acknowledgeResponse(googleAccount, envelope, myAppId)
            } catch (e: Exception) {
                // Swallowing this without logging used to mean a single bad envelope (or a
                // silently-failing acknowledge) could pile up unbounded — the envelope never
                // gets deleted, so it's re-fetched and re-fails on every subsequent poll forever.
                android.util.Log.e("CalibreRepository", "pollResponses: failed to process/acknowledge envelope id=${envelope.id} source=${envelope.source} content=${envelope.content}", e)
            }
            _syncProgress.value = SyncProgress(
                SyncPhase.PULLING_RESPONSES, "Processing daemon responses (${index + 1}/${envelopes.size})...", index + 1, envelopes.size,
            )
        }
        // Whoever called us — background loop or a manual full sync — is done with this leg of
        // work. A manual sync overwrites this immediately with its next phase; a bare background
        // call needs this so the banner actually goes away instead of showing stale "done" text
        // until the next poll cycle 10s+ later.
        _syncProgress.value = null
    }

    suspend fun createPlexAssembly(
        file: PutioFile,
        movieTitle: String,
        year: String,
        destPath: String,
        assembleMode: Boolean,
        googleAccount: String,
        createFolder: Boolean = true,
    ) {
        val displayName = file.displayName
        // CONTRACT: stub convention — use original file ID + local_path for synced files
        val localPath = readStubLocalPath(file)
        val movieItem = PlexAssemblyItem(
            putio_file_id = file.syncedFileId,
            fileName = displayName,
            item_type = "MOVIE",
            use_local = if (localPath != null) true else null,
            local_path = localPath,
            stub_putio_id = if (file.isSynced) file.id else null,
        )
        val batchData = PlexBatchData(movie_title = movieTitle, year = year, dest_path = destPath, items = listOf(movieItem), create_folder = createFolder)
        val folderLabel = if (year.isNotBlank()) "$movieTitle ($year)" else movieTitle
        val anchorId = file.syncedFileId

        val transfer = CalibreTransferEntity(
            putioFileId = anchorId,
            fileName = displayName,
            title = folderLabel,
            author = destPath.ifBlank { "Plex root" },
            status = if (assembleMode) CalibreTransferStatus.ASSEMBLED else CalibreTransferStatus.PENDING,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = anchorId.toString(),
            transferType = "PLEX",
            batchData = json.encodeToString(batchData),
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
        if (assembleMode) return

        val appId = settingsRepository.getOrCreateAppId()
        val request = plexRequestFromBatchData(batchData, anchorId, appId)
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_plex_$anchorId.json", jsonStr)
        withContext(NonCancellable) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
                gdriveRequestId = gDriveId,
                errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
                lastRequestPayload = jsonStr,
            ))
        }
    }

    suspend fun appendSubtitleToPlexAssembly(
        assemblyFileId: Long,
        subtitle: PutioFile,
        language: String,
    ): String? {
        val transfer = calibreTransferDao.getTransferById(assemblyFileId) ?: return "Assembly not found"
        if (transfer.transferType != "PLEX") return "Not a Plex assembly"
        val batchData = transfer.batchData?.let {
            try { json.decodeFromString<PlexBatchData>(it) } catch (e: Exception) { return "Could not parse assembly data: ${e.message}" }
        } ?: return "No batch data"

        val existingLanguages = batchData.items.filter { it.item_type == "SUBTITLE" }.mapNotNull { it.language }.toSet()
        if (language in existingLanguages) return "This language is already in the assembly"

        // CONTRACT: stub convention — use original file ID + local_path for synced subtitles
        val localPath = readStubLocalPath(subtitle)
        val newItem = PlexAssemblyItem(
            putio_file_id = subtitle.syncedFileId,
            fileName = subtitle.displayName,
            item_type = "SUBTITLE",
            language = language,
            use_local = if (localPath != null) true else null,
            local_path = localPath,
            stub_putio_id = if (subtitle.isSynced) subtitle.id else null,
        )
        val updated = batchData.copy(items = batchData.items + newItem)
        val updatedIds = (transfer.parsedFileIds() + subtitle.syncedFileId).distinct()
        calibreTransferDao.updateTransfer(transfer.copy(
            batchData = json.encodeToString(updated),
            allPutioFileIds = updatedIds.joinToString(","),
            lastUpdatedAt = System.currentTimeMillis(),
        ))
        return null
    }

    suspend fun sendAddSubtitleToMovieRequest(
        subtitle: PutioFile,
        language: String,
        movieFolderPath: String,
        movieFileName: String,
        googleAccount: String,
    ) {
        // CONTRACT: stub convention — use original file ID + local_path for synced subtitles
        val localPath = readStubLocalPath(subtitle)
        val anchorId = subtitle.syncedFileId
        val appId = settingsRepository.getOrCreateAppId()
        val request = PlexAddSubtitleRequest(
            putio_file_id = anchorId,
            language = language,
            movie_folder_path = movieFolderPath,
            movie_file_name = movieFileName,
            local_path = localPath,
            stub_putio_id = if (subtitle.isSynced) subtitle.id else null,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_subtitle_$anchorId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = anchorId,
            fileName = subtitle.displayName,
            title = "Add subtitle → ${movieFileName.substringBeforeLast('.')}",
            author = movieFolderPath.ifBlank { "Plex" },
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = anchorId.toString(),
            transferType = "PLEX",
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    private fun plexRequestFromBatchData(batchData: PlexBatchData, anchorFileId: Long, appId: String? = null) = PlexTransferRequest(
        putio_file_id = anchorFileId,
        movie_title = batchData.movie_title,
        year = batchData.year,
        dest_path = batchData.dest_path,
        items = batchData.items,
        create_folder = batchData.create_folder,
        app_id = appId,
    )

    private suspend fun retryPlexTransfer(transfer: CalibreTransferEntity, googleAccount: String): NetworkResult<Unit> {
        val appId = settingsRepository.getOrCreateAppId()
        val payload = transfer.lastRequestPayload ?: run {
            val batchData = transfer.batchData?.let {
                try { json.decodeFromString<PlexBatchData>(it) } catch (e: Exception) {
                    return NetworkResult.Error("Could not parse Plex assembly data")
                }
            } ?: return NetworkResult.Error("No batch data for Plex transfer")
            json.encodeToString(plexRequestFromBatchData(batchData, transfer.putioFileId, appId))
        }
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_plex_${transfer.putioFileId}.json", payload)
        return if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                retryCount = if (transfer.status == CalibreTransferStatus.FAILED) transfer.retryCount + 1 else transfer.retryCount,
                errorMessage = null,
                lastRequestPayload = payload,
            ))
            NetworkResult.Success(Unit)
        } else {
            NetworkResult.Error("Could not upload Plex request to Google Drive")
        }
    }

    private suspend fun retryPlexampTransfer(transfer: CalibreTransferEntity, googleAccount: String): NetworkResult<Unit> {
        val payload = transfer.lastRequestPayload
            ?: return NetworkResult.Error("Original Plexamp request payload was not saved and cannot be reconstructed")
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_plexamp_${transfer.putioFileId}.json", payload)
        return if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                retryCount = if (transfer.status == CalibreTransferStatus.FAILED) transfer.retryCount + 1 else transfer.retryCount,
                errorMessage = null,
                lastRequestPayload = payload,
            ))
            NetworkResult.Success(Unit)
        } else {
            NetworkResult.Error("Could not upload Plexamp request to Google Drive")
        }
    }

    // CONTRACT: SEND_TO_PLEXAMP
    suspend fun sendToPlexamp(
        files: List<PutioFile>,
        artistName: String,
        albumName: String,
        createFolder: Boolean,
        destPath: String,
        googleAccount: String,
    ) {
        val anchorId = files.first().syncedFileId
        val appId = settingsRepository.getOrCreateAppId()
        val items = files.map { file ->
            val localPath = readStubLocalPath(file)
            PlexampItem(
                putio_file_id = file.syncedFileId,
                fileName = file.displayName,
                use_local = if (localPath != null) true else null,
                local_path = localPath,
                stub_putio_id = if (file.isSynced) file.id else null,
            )
        }
        val request = PlexampTransferRequest(
            putio_file_id = anchorId,
            artist_name = artistName,
            album_name = albumName,
            create_folder = createFolder,
            dest_path = destPath,
            items = items,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_plexamp_$anchorId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = anchorId,
            fileName = files.first().displayName,
            title = "$artistName — $albumName",
            author = if (createFolder) artistName else destPath.ifBlank { "Plexamp root" },
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = files.map { it.syncedFileId }.distinct().joinToString(","),
            transferType = "PLEXAMP",
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: FUSE_BOOKS / JOIN_BOOKS — shared upload path; request.action picks which.
    suspend fun sendFuseBooksRequest(
        request: FuseBooksRequest,
        displayTitle: String,
        googleAccount: String,
    ) {
        val isJoin = request.action == "JOIN_BOOKS"
        val appId = settingsRepository.getOrCreateAppId()
        val jsonStr = json.encodeToString(request.copy(app_id = appId))
        val gDriveId = daemonTransport.submitRequest(
            googleAccount,
            "req_${if (isJoin) "join" else "fuse"}_${request.putio_file_id}.json",
            jsonStr,
        )
        val transfer = CalibreTransferEntity(
            putioFileId = request.putio_file_id,
            fileName = "${if (isJoin) "Joining" else "Fusing"} ${request.source_book_ids.size} books",
            title = displayTitle,
            author = request.metadata.authors,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = request.putio_file_id.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            transferType = if (isJoin) "JOIN" else "FUSION",
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: MOVE_FORMAT
    suspend fun sendMoveFormatRequest(
        sourceBookUuid: String,
        destBookUuid: String,
        format: String,
        displayTitle: String,
        displayAuthor: String,
        googleAccount: String,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, book-level-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = MoveFormatRequest(
            putio_file_id = putioFileId,
            source_book_uuid = sourceBookUuid,
            dest_book_uuid = destBookUuid,
            format = format,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_move_format_$putioFileId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Move $format to \"$displayTitle\"",
            title = displayTitle,
            author = displayAuthor,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            transferType = "MOVE_FORMAT",
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: MANAGE_VIRTUAL_LIBRARY
    suspend fun sendManageVirtualLibraryRequest(
        request: ManageVirtualLibraryRequest,
        googleAccount: String,
    ) {
        val appId = settingsRepository.getOrCreateAppId()
        val jsonStr = json.encodeToString(request.copy(app_id = appId))
        val displayName = when (request.operation) {
            "CREATE" -> "Create VL \"${request.name}\""
            "RENAME" -> "Rename VL \"${request.name}\" → \"${request.new_name}\""
            "UPDATE_QUERY" -> "Update VL \"${request.name}\""
            "DELETE" -> "Delete VL \"${request.name}\""
            else -> "Manage VL \"${request.name}\""
        }
        val gDriveId = daemonTransport.submitRequest(
            googleAccount,
            "req_manage_vl_${request.putio_file_id}.json",
            jsonStr,
        )
        val transfer = CalibreTransferEntity(
            putioFileId = request.putio_file_id,
            fileName = displayName,
            title = displayName,
            author = "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = request.putio_file_id.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            transferType = "MANAGE_VL",
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: RENAME_AUTHOR
    suspend fun sendRenameAuthorRequest(
        request: RenameAuthorRequest,
        googleAccount: String,
    ) {
        val appId = settingsRepository.getOrCreateAppId()
        val jsonStr = json.encodeToString(request.copy(app_id = appId))
        val displayName = "Rename author → \"${request.new_name}\""
        val gDriveId = daemonTransport.submitRequest(
            googleAccount,
            "req_rename_author_${request.putio_file_id}.json",
            jsonStr,
        )
        val transfer = CalibreTransferEntity(
            putioFileId = request.putio_file_id,
            fileName = displayName,
            title = displayName,
            author = "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = request.putio_file_id.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            transferType = "RENAME_AUTHOR",
            lastRequestPayload = jsonStr,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    suspend fun checkExistsByUuid(dbFile: File, uuid: String): CalibreBookMatch? {
        checkExistsByUuidLocal(dbFile, uuid)?.let { return it }
        // Local metadata.db copy has protected/encrypted books' rows split out of it (see
        // CONTRACTS.md §3a "protection split"), so a local miss doesn't mean the book doesn't
        // exist. Ask the daemon directly over LAN — it reads the live, unsplit metadata.db.
        val lanEnabled = settingsRepository.lanEnabledFlow.first()
        if (!lanEnabled || !lanDaemonTransport.isReachable()) return null
        val match = lanDaemonTransport.getBookByUuid(uuid) ?: return null
        return CalibreBookMatch(id = match.id, title = match.title, author = match.author, tags = match.tags)
    }

    private suspend fun checkExistsByUuidLocal(dbFile: File, uuid: String): CalibreBookMatch? = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext null
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val query = """
                    SELECT books.id, books.title, authors.name FROM books
                    JOIN books_authors_link ON books.id = books_authors_link.book
                    JOIN authors ON authors.id = books_authors_link.author
                    WHERE books.uuid = ?
                """.trimIndent()

                db.rawQuery(query, arrayOf(uuid)).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val bookId = cursor.getLong(0)
                        return@withContext CalibreBookMatch(
                            id = bookId,
                            title = cursor.getString(1),
                            author = cursor.getString(2),
                            tags = getBookTags(db, bookId),
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // CONTRACT: protection split — Putz's downloaded metadata.db has protected/encrypted
    // books' rows split out of it (see calibre_assets/protection_split.py), so a uuid "not
    // found" here is ambiguous: genuinely missing, or just protected and invisible to this
    // local copy. Only the daemon's live, unsplit metadata.db can tell the two apart — hence
    // the sendProbeRequest fallback below for any action whose success requires the book to
    // exist. These are the exceptions: actions whose verification either needs no uuid lookup
    // at all, or where the book being ABSENT locally is the expected success state (deletion
    // confirmations), so a missing row there is not the split-table ambiguity and must not
    // trigger a fallback probe.
    private val NO_PRESENCE_CHECK_ACTIONS = setOf(
        "PRIORITY_PUTIO_SYNC", "MARK_BOOK_FOR_DELETION", "MARK_FORMATS_FOR_DELETION",
        "CONFIRM_DELETE_BOOK", "CONFIRM_DELETE_FORMATS", "CANCEL_DELETION",
    )

    // Caps how many verifyCompletedTransfers probes (each a Drive API round trip) run at once —
    // enough to erase the "sum of every sequential round trip" slowdown without risking Drive
    // API rate limits when a large backlog of protected/split books needs probing at once.
    private val PROBE_CONCURRENCY = 5

    suspend fun verifyCompletedTransfers(
        dbFile: File,
        googleAccount: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val transfers = calibreTransferDao.getAllTransfers().first().filter {
            it.status == CalibreTransferStatus.COMPLETED && !it.libraryVerified
        }
        if (transfers.isEmpty() || !dbFile.exists()) return@withContext

        val db = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
        } catch (e: Exception) { return@withContext }

        // Most transfers resolve via a fast local SQLite read against dbFile, but any whose book
        // isn't found there (split out for protection — see rowMissingLocally below) fall back to
        // sendProbeRequest(), a real Google Drive API round trip. Run those concurrently instead
        // of one at a time — a backlog of protected books used to mean minutes of "10s per
        // transfer" here, entirely spent waiting on sequential network calls one after another.
        // Bounded (rather than fully parallel) to avoid tripping Drive API rate limits when the
        // backlog is large.
        val progressCount = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = Semaphore(PROBE_CONCURRENCY)
        db.use {
            coroutineScope {
                transfers.map { transfer ->
                    async {
                        semaphore.withPermit {
                            verifyOneCompletedTransfer(db, transfer, googleAccount)
                            onProgress?.invoke(progressCount.incrementAndGet(), transfers.size)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun verifyOneCompletedTransfer(
        db: android.database.sqlite.SQLiteDatabase,
        transfer: CalibreTransferEntity,
        googleAccount: String,
    ) {
        // One malformed row (bad batchData JSON, an unexpected schema edge case, etc.) must not
        // abort verification for the rest of the batch — this loop can be processing hundreds of
        // transfers, and an uncaught exception here used to kill the whole pass silently
        // (verifyCompletedTransfers has no catch of its own, and neither does its caller,
        // syncMetadata — so it just looked like a no-op sync with no error shown, leaving
        // everything after the bad row stuck unverified).
        try {
            val action = transfer.lastRequestPayload?.let { payload ->
                try {
                    json.parseToJsonElement(payload).jsonObject["action"]?.jsonPrimitive?.content
                } catch (_: Exception) { null }
            }
            // Local row missing entirely for an action that requires the book to exist: don't
            // treat it as a hard failure yet, since it may just be split out for protection. Ask
            // the daemon (below) instead of leaving it stuck unverified forever — this is the
            // fallback ADD_BOOK_BATCH (and anything else landing in the `else` branch below)
            // needs just as much as REPLACE_COVER etc. do.
            val rowMissingLocally = transfer.calibreBookUuid != null &&
                transfer.transferType != "PLEX" &&
                action !in NO_PRESENCE_CHECK_ACTIONS &&
                !checkBookUuidExists(db, transfer.calibreBookUuid)

            val verified = when {
                // Non-Calibre actions need no library check
                transfer.transferType == "PLEX" -> true
                action == "PRIORITY_PUTIO_SYNC" -> true
                // Must have a UUID to locate the book
                transfer.calibreBookUuid == null -> true
                rowMissingLocally -> false
                action == "REPLACE_COVER" -> checkCoverVerified(db, transfer.calibreBookUuid)
                action == "GENERATE_COVER" -> checkCoverVerified(db, transfer.calibreBookUuid)
                action == "EXTRACT_OR_RANDOM_COVER" -> checkCoverVerified(db, transfer.calibreBookUuid)
                action == "PROTECT_BOOK" -> checkCoverVerified(db, transfer.calibreBookUuid)
                action == "UPDATE_COMMENTS" -> checkBookUuidExists(db, transfer.calibreBookUuid)
                // Mark-for-deletion: daemon COMPLETED already confirmed feasibility; the book may
                // have since been deleted, so don't require it to still exist.
                action == "MARK_BOOK_FOR_DELETION" -> true
                action == "MARK_FORMATS_FOR_DELETION" -> true
                // Confirm-delete: verify the book / formats are gone from the library
                action == "CONFIRM_DELETE_BOOK" -> !checkBookUuidExists(db, transfer.calibreBookUuid)
                action == "CONFIRM_DELETE_FORMATS" -> checkFormatsDeleted(db, transfer.calibreBookUuid, transfer.lastRequestPayload)
                // Cancel-deletion is a client-side acknowledgement; no library state changes
                action == "CANCEL_DELETION" -> true
                else -> checkFormatsVerified(db, transfer.calibreBookUuid, transfer.batchData)
            }
            if (verified) {
                calibreTransferDao.updateTransfer(
                    transfer.copy(libraryVerified = true, lastUpdatedAt = System.currentTimeMillis())
                )
            } else if (rowMissingLocally && googleAccount.isNotBlank()) {
                // Daemon checks against its live, unsplit metadata.db (see find_book_by_uuid in
                // putz_manager.py), so it can confirm a protected book that this local copy can't
                // see. Response comes back through the normal probe path in pollResponses(),
                // which marks libraryVerified.
                sendProbeRequest(transfer.putioFileId, googleAccount)
            }
        } catch (e: Exception) {
            android.util.Log.e("CalibreRepository",
                "verifyCompletedTransfers: failed for putio_file_id=${transfer.putioFileId} — skipping, will retry next sync", e)
        }
    }

    private fun checkBookUuidExists(db: android.database.sqlite.SQLiteDatabase, uuid: String): Boolean {
        return db.rawQuery("SELECT 1 FROM books WHERE uuid = ?", arrayOf(uuid)).use { it.moveToFirst() }
    }

    private fun checkCoverVerified(db: android.database.sqlite.SQLiteDatabase, uuid: String): Boolean {
        return db.rawQuery("SELECT has_cover FROM books WHERE uuid = ?", arrayOf(uuid)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    private fun checkFormatsVerified(
        db: android.database.sqlite.SQLiteDatabase,
        uuid: String,
        batchDataJson: String?,
    ): Boolean {
        val bookId = db.rawQuery("SELECT id FROM books WHERE uuid = ?", arrayOf(uuid)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else return false
        }
        val libraryFormats = db.rawQuery("SELECT format FROM data WHERE book = ?", arrayOf(bookId.toString())).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val expectedFormats = expectedFormats(batchDataJson)
        return libraryFormats.containsAll(expectedFormats)
    }

    private fun checkFormatsDeleted(
        db: android.database.sqlite.SQLiteDatabase,
        uuid: String,
        lastRequestPayload: String?,
    ): Boolean {
        // If the book itself is gone the formats are definitely deleted too
        val bookId = db.rawQuery("SELECT id FROM books WHERE uuid = ?", arrayOf(uuid)).use { cursor ->
            if (!cursor.moveToFirst()) return true
            cursor.getLong(0)
        }
        val formats = lastRequestPayload?.let { payload ->
            try {
                json.parseToJsonElement(payload).jsonObject["formats"]
                    ?.jsonArray?.map { it.jsonPrimitive.content.uppercase() }?.toSet()
            } catch (_: Exception) { null }
        } ?: return false
        if (formats.isEmpty()) return false
        val libraryFormats = db.rawQuery(
            "SELECT format FROM data WHERE book = ?", arrayOf(bookId.toString())
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return formats.none { it in libraryFormats }
    }

    private fun expectedFormats(batchDataJson: String?): Set<String> {
        val items = batchDataJson?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        } ?: return emptySet()
        return items.flatMap { item ->
            when (item.type) {
                "PACK" -> listOf("M4B")
                // Archives can produce any set of formats; just verify the book exists
                "ARCHIVE", "ARCHIVE_ENTRY" -> emptyList()
                else -> {
                    val ext = item.fileName.substringAfterLast('.', "").uppercase()
                    // Daemon converts PRC to EPUB; Calibre auto-zips HTML/HTM on import
                    when {
                        ext == "PRC" -> listOf("EPUB")
                        ext == "HTML" || ext == "HTM" -> listOf("ZIP")
                        ext.isNotEmpty() && ext.length <= 5 && !ext.contains(' ') -> listOf(ext)
                        else -> emptyList()
                    }
                }
            }
        }.toSet()
    }

    private fun getBookTags(db: android.database.sqlite.SQLiteDatabase, bookId: Long): String {
        val query = """
            SELECT tags.name FROM tags
            JOIN books_tags_link ON tags.id = books_tags_link.tag
            WHERE books_tags_link.book = ?
            ORDER BY tags.name COLLATE NOCASE
        """.trimIndent()
        return db.rawQuery(query, arrayOf(bookId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }.joinToString(", ")
        }
    }

    @Serializable
    data class StubContent(val local_path: String? = null, val file_size: Long? = null)

    /** Distinguishes *why* a stub couldn't be resolved, so callers can tell "this file's stub
     *  is gone (already processed / deleted, list is stale)" apart from "transient network or
     *  auth failure" instead of collapsing both into the same generic message. */
    sealed class StubFetchResult {
        data class Success(val content: StubContent) : StubFetchResult()
        /** The put.io file id for this stub returned 404/410 — it no longer exists server-side. */
        data class NotFound(val code: Int) : StubFetchResult()
        data class Failed(val message: String, val code: Int? = null) : StubFetchResult()
    }

    // Stub content is immutable once written (CONTRACT: stub convention), so the resolved
    // content for a given stub's put.io ID never changes — cache it for the process lifetime
    // to avoid re-fetching it every time the Files screen reloads the same folder.
    private val stubContentCache = java.util.concurrent.ConcurrentHashMap<Long, StubContent>()
    private val stubRawJsonCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    // CONTRACT: stub convention — read raw stub JSON by put.io file ID; the single network call
    // backing readStubLocalPath(By Id)/readStubFileSize/readStubContent below
    private suspend fun fetchStubContentResult(stubFileId: Long): StubFetchResult {
        stubContentCache[stubFileId]?.let { return StubFetchResult.Success(it) }
        val token = secureStorage.authTokenFlow.value
        if (token.isBlank()) return StubFetchResult.Failed("Not signed in to put.io")
        return withContext(Dispatchers.IO) {
            when (val result = putioApiClient.downloadFileAsString(token, stubFileId)) {
                is NetworkResult.Success -> {
                    stubRawJsonCache[stubFileId] = result.data
                    try {
                        val content = json.decodeFromString<StubContent>(result.data)
                        stubContentCache[stubFileId] = content
                        StubFetchResult.Success(content)
                    } catch (e: Exception) {
                        StubFetchResult.Failed(e.message ?: "Stub content could not be parsed")
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404 || result.code == 410) StubFetchResult.NotFound(result.code)
                    else StubFetchResult.Failed(result.message, result.code)
                }
                is NetworkResult.Loading -> StubFetchResult.Failed("Unexpected loading state")
            }
        }
    }

    private suspend fun fetchStubContent(stubFileId: Long): StubContent? =
        (fetchStubContentResult(stubFileId) as? StubFetchResult.Success)?.content

    // CONTRACT: stub convention — read the stub's JSON body exactly as the daemon wrote it
    // (includes fields StubContent doesn't model, e.g. synced_at). Used by "Copy JSON" in the
    // Files screen kebab menu; returns null if not synced or on error.
    suspend fun readStubRawJson(file: com.damarquez.putz.data.model.PutioFile): String? {
        if (!file.isSynced) return null
        stubRawJsonCache[file.id]?.let { return it }
        fetchStubContent(file.id)
        return stubRawJsonCache[file.id]
    }

    // CONTRACT: stub convention — read local_path from stub JSON by file ID; for use when PutioFile is unavailable
    suspend fun readStubLocalPathById(stubFileId: Long): String? = fetchStubContent(stubFileId)?.local_path

    // CONTRACT: stub convention — read local_path from stub JSON; returns null if not synced or on error
    suspend fun readStubLocalPath(file: com.damarquez.putz.data.model.PutioFile): String? {
        if (!file.isSynced) return null
        return fetchStubContent(file.id)?.local_path
    }

    /** Distinguishes why local_path couldn't be resolved (see [StubFetchResult]) — used at
     *  send-to-Calibre dispatch time, where showing "check your connection" for a stub that's
     *  actually 404 (already processed/deleted, list just hasn't refreshed) is misleading. */
    sealed class StubLocalPathResult {
        data class Resolved(val localPath: String?) : StubLocalPathResult()
        data class NotFound(val code: Int) : StubLocalPathResult()
        data class Failed(val message: String) : StubLocalPathResult()
    }

    suspend fun readStubLocalPathOrError(file: com.damarquez.putz.data.model.PutioFile): StubLocalPathResult {
        if (!file.isSynced) return StubLocalPathResult.Failed("File is not synced")
        return when (val result = fetchStubContentResult(file.id)) {
            is StubFetchResult.Success -> StubLocalPathResult.Resolved(result.content.local_path)
            is StubFetchResult.NotFound -> StubLocalPathResult.NotFound(result.code)
            is StubFetchResult.Failed -> StubLocalPathResult.Failed(result.message)
        }
    }

    // CONTRACT: stub convention — read the original file's real size, not the tiny stub's
    // put.io-reported size; returns null if not synced, unavailable, or on error.
    // Checks the stub filename's embedded "<size>~~" prefix first — free, no network call —
    // and only falls back to downloading/parsing the stub's JSON body for stubs that predate
    // that feature (or haven't been backfilled by the daemon's size-migration walk yet).
    suspend fun readStubFileSize(file: com.damarquez.putz.data.model.PutioFile): Long? {
        if (!file.isSynced) return null
        file.sizeFromStubName?.let { return it }
        return fetchStubContent(file.id)?.file_size
    }

    // CONTRACT: stub convention — read the full stub JSON (local_path + file_size); returns null if
    // not synced or on error. Used to show stub details on tap, since the stub's display name is
    // often truncated in the UI.
    suspend fun readStubContent(file: com.damarquez.putz.data.model.PutioFile): StubContent? {
        if (!file.isSynced) return null
        return fetchStubContent(file.id)
    }

    // CONTRACT: book duplicate matching (see CONTRACTS.md). Normalization and author-token
    // matching here must stay behaviorally equivalent to the daemon's _normalize_text()/
    // _author_name_tokens()/find_book_any_author() in putz_manager.py — this is the
    // client-side "might already exist" prediction, the daemon's dedup check is the actual
    // decision, and if they disagree the UI warning stops meaning anything.
    private fun normalize(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val authorSeparatorRegex = Regex("[&,;/.()\\[\\]]|\\band\\b")

    // Splits an already-normalized author string into a set of lowercase word tokens,
    // ignoring punctuation/diacritics — mirrors the daemon's _author_name_tokens().
    private fun authorTokens(normalizedAuthor: String): Set<String> =
        authorSeparatorRegex.replace(normalizedAuthor, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()

    // Match if either author string is missing/"unknown", one normalized string contains
    // the other, or the two author-token sets overlap at all (handles Calibre's own author
    // normalization, e.g. "Shadow and Claw" stored as "Claw, Shadow and", as well as
    // differently formatted multi-author strings, e.g. "A, B (ed)" vs "A & B (ed)").
    private fun authorsMatch(author: String, dbAuthor: String): Boolean {
        val normAuthor = normalize(author)
        val normDbAuthor = normalize(dbAuthor)
        if (normDbAuthor.isBlank() || normDbAuthor == "unknown") return true
        if (normAuthor.isBlank() || normAuthor == "unknown") return true
        if (normDbAuthor.contains(normAuthor) || normAuthor.contains(normDbAuthor)) return true
        return authorTokens(normAuthor).intersect(authorTokens(normDbAuthor)).isNotEmpty()
    }

    suspend fun checkExists(dbFile: File, title: String, author: String): Long? = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext null
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                // First, try direct search with accents
                val query = """
                    SELECT books.id, books.title, authors.name FROM books
                    JOIN books_authors_link ON books.id = books_authors_link.book
                    JOIN authors ON authors.id = books_authors_link.author
                """.trimIndent()

                db.rawQuery(query, null).use { cursor ->
                    val normTitle = normalize(title)

                    if (cursor.moveToFirst()) {
                        do {
                            val id = cursor.getLong(0)
                            val dbTitle = cursor.getString(1)
                            val dbAuthor = cursor.getString(2)

                            val normDbTitle = normalize(dbTitle)

                            // Title must match exactly (after accent/case/whitespace
                            // normalization) — a substring/contains check here previously
                            // matched any title that merely *mentioned* the search title
                            // elsewhere, e.g. searching "The Willows" matching the anthology
                            // "Ancient Sorceries (The Listener; The Sea Fit; The Willows)" and
                            // wrongly warning it might already exist. Author matching is
                            // token-based — see authorsMatch().
                            if (normDbTitle == normTitle && authorsMatch(author, dbAuthor)) {
                                return@withContext id
                            }
                        } while (cursor.moveToNext())
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // CONTRACT: CHAIN — shared tail for the "build one entity, decide status, maybe dispatch"
    // send*Request functions. When addToChain is true, stages the transfer as CHAINED (ordered
    // by a fresh chainPosition) and skips dispatch entirely — the daemon never sees it until
    // placeChain() later wraps it (and its chain siblings) into one CHAIN request. Otherwise
    // dispatches now via daemonTransport, exactly as every one of these functions did before
    // this helper existed. [buildTransfer] receives the resolved status/gdriveRequestId/
    // errorMessage/chainPosition and returns the rest of the entity's fields.
    private suspend fun dispatchOrStage(
        googleAccount: String,
        requestFileName: String,
        jsonStr: String,
        addToChain: Boolean,
        isPriority: Boolean = false,
        buildTransfer: (status: CalibreTransferStatus, gDriveId: String?, errorMessage: String?, chainPosition: Int?) -> CalibreTransferEntity,
    ) {
        if (addToChain) {
            val nextPosition = (calibreTransferDao.maxChainPosition() ?: -1) + 1
            val transfer = buildTransfer(CalibreTransferStatus.CHAINED, null, null, nextPosition)
            withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
            return
        }
        val gDriveId = daemonTransport.submitRequest(googleAccount, requestFileName, jsonStr, isPriority)
        val transfer = buildTransfer(
            if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            gDriveId,
            if (gDriveId == null) "Failed to upload to GDrive" else null,
            null,
        )
        // NonCancellable: the GDrive upload already completed; the DB record must always
        // be written so the transfer is visible even if the calling scope navigates away.
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    suspend fun sendReplaceCoverRequest(
        putioFileId: Long,
        fileName: String,
        title: String,
        author: String,
        calibreBookId: Long,
        googleAccount: String,
        downloadUrl: String? = null,
        calibreBookUuid: String? = null,
        useLocal: Boolean = false,
        localPath: String? = null,
        addToChain: Boolean = false,
        isPriority: Boolean = false,
    ) {
        val item = CalibreBatchItem(
            type = "SINGLE",
            putio_file_id = putioFileId,
            fileName = fileName,
            download_url = downloadUrl,
            use_local = if (useLocal) true else null,
            local_path = localPath,
        )
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "REPLACE_COVER",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = listOf(item),
            calibre_book_id = calibreBookId,
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        dispatchOrStage(googleAccount, "req_cover_$putioFileId.json", jsonStr, addToChain, isPriority) { status, gDriveId, errorMessage, chainPosition ->
            CalibreTransferEntity(
                putioFileId = putioFileId,
                fileName = fileName,
                title = "Cover for $title",
                author = author,
                status = status,
                addedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                allPutioFileIds = putioFileId.toString(),
                gdriveRequestId = gDriveId,
                errorMessage = errorMessage,
                batchData = json.encodeToString(listOf(item)),
                calibreBookUuid = calibreBookUuid,
                lastRequestPayload = jsonStr,
                chainPosition = chainPosition,
                priority = isPriority,
            )
        }
    }

    /** Uploads a clipboard image to `.putz_attachments` on put.io and stashes it on [transferId]'s
     *  row as a not-yet-applied cover. See pollResponses' COMPLETED handling — once this
     *  (ASSEMBLED/CHAINED) request completes and its real calibre_book_uuid is known, the stashed
     *  download URL is used to fire sendReplaceCoverRequest() automatically, the same way
     *  calibreAnywhere's putz://replace_cover flow already does for existing books. */
    suspend fun attachClipboardCoverToAssembly(transferId: Long, imageUri: android.net.Uri, googleAccount: String): Boolean {
        val token = secureStorage.authTokenFlow.value
        if (token.isBlank()) return false
        val transfer = calibreTransferDao.getTransferById(transferId) ?: return false

        val rootFiles = withContext(Dispatchers.IO) {
            (putioApiClient.listFiles(token, 0) as? NetworkResult.Success)?.data?.first ?: emptyList()
        }
        var folderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id
        if (folderId == null) {
            val result = withContext(Dispatchers.IO) { putioApiClient.createFolder(token, 0, ".putz_attachments") }
            folderId = (result as? NetworkResult.Success)?.data?.id
        }
        val resolvedFolderId = folderId ?: return false

        val fileName = "clipboard_cover_${System.currentTimeMillis()}.jpg"
        val uploadResult = withContext(Dispatchers.IO) {
            putioApiClient.uploadFile(token, resolvedFolderId, fileName, imageUri, context.contentResolver) { _, _ -> }
        }
        val uploaded = (uploadResult as? NetworkResult.Success)?.data ?: return false
        val downloadUrl = "${PutioApiClient.BASE_URL}/files/${uploaded.id}/download?oauth_token=$token"

        calibreTransferDao.updateTransfer(transfer.copy(
            pendingCoverPutioFileId = uploaded.id,
            pendingCoverDownloadUrl = downloadUrl,
            pendingCoverFileName = fileName,
            lastUpdatedAt = System.currentTimeMillis(),
        ))
        return true
    }

    // CONTRACT: GENERATE_COVER
    suspend fun sendGenerateCoverRequest(
        title: String,
        author: String,
        calibreBookUuid: String,
        googleAccount: String,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "GENERATE_COVER",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_gencover_$putioFileId.json", jsonStr)

        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Generate cover for $title",
            title = "Generate cover for $title",
            author = author,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: EXTRACT_OR_RANDOM_COVER
    suspend fun sendExtractOrRandomCoverRequest(
        title: String,
        author: String,
        calibreBookUuid: String,
        googleAccount: String,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "EXTRACT_OR_RANDOM_COVER",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_extractorrandomcover_$putioFileId.json", jsonStr)

        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Extract or generate cover for $title",
            title = "Extract or generate cover for $title",
            author = author,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: PROTECT_BOOK
    suspend fun sendProtectBookRequest(
        title: String,
        author: String,
        calibreBookUuid: String,
        googleAccount: String,
        keepCover: Boolean = false,
        addToChain: Boolean = false,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "PROTECT_BOOK",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_uuid = calibreBookUuid,
            keep_cover = if (keepCover) true else null,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        dispatchOrStage(googleAccount, "req_protect_$putioFileId.json", jsonStr, addToChain) { status, gDriveId, errorMessage, chainPosition ->
            CalibreTransferEntity(
                putioFileId = putioFileId,
                fileName = "Protect $title",
                title = "Protect $title",
                author = author,
                status = status,
                addedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                allPutioFileIds = putioFileId.toString(),
                gdriveRequestId = gDriveId,
                errorMessage = errorMessage,
                batchData = "[]",
                calibreBookUuid = calibreBookUuid,
                lastRequestPayload = jsonStr,
                hasPutioFile = false,
                chainPosition = chainPosition,
            )
        }
    }

    // CONTRACT: UNPROTECT_BOOK
    suspend fun sendUnprotectBookRequest(
        title: String,
        author: String,
        calibreBookUuid: String,
        googleAccount: String,
        addToChain: Boolean = false,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "UNPROTECT_BOOK",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        dispatchOrStage(googleAccount, "req_unprotect_$putioFileId.json", jsonStr, addToChain) { status, gDriveId, errorMessage, chainPosition ->
            CalibreTransferEntity(
                putioFileId = putioFileId,
                fileName = "Unprotect $title",
                title = "Unprotect $title",
                author = author,
                status = status,
                addedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                allPutioFileIds = putioFileId.toString(),
                gdriveRequestId = gDriveId,
                errorMessage = errorMessage,
                batchData = "[]",
                calibreBookUuid = calibreBookUuid,
                lastRequestPayload = jsonStr,
                hasPutioFile = false,
                chainPosition = chainPosition,
            )
        }
    }

    // CONTRACT: CONVERT_FORMAT
    suspend fun sendConvertFormatRequest(
        title: String,
        author: String,
        calibreBookUuid: String,
        sourceFormat: String,
        targetFormat: String,
        googleAccount: String,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = "CONVERT_FORMAT",
            putio_file_id = putioFileId,
            title = title,
            author = author,
            items = emptyList(),
            calibre_book_uuid = calibreBookUuid,
            source_format = sourceFormat,
            target_format = targetFormat,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_convert_$putioFileId.json", jsonStr)

        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Convert $title to $targetFormat",
            title = "Convert $title to $targetFormat",
            author = author,
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: SET_PAGE_COUNT
    suspend fun sendSetPageCountRequest(
        calibreBookUuid: String,
        pageCount: Int,
        googleAccount: String,
        title: String? = null,
        author: String? = null,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = SetPageCountRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = calibreBookUuid,
            page_count = pageCount,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_setpages_$putioFileId.json", jsonStr)

        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Set page count to $pageCount",
            title = title ?: "Set page count to $pageCount",
            author = author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: MARK_BOOK_FOR_DELETION
    suspend fun sendMarkBookForDeletionRequest(calibreBookUuid: String, googleAccount: String, title: String? = null, author: String? = null) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = MarkBookForDeletionRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_markdel_$putioFileId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Mark book for deletion",
            title = title ?: "Mark book for deletion",
            author = author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: MARK_FORMATS_FOR_DELETION
    suspend fun sendMarkFormatsForDeletionRequest(
        calibreBookUuid: String,
        formats: List<String>,
        googleAccount: String,
        title: String? = null,
        author: String? = null,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = MarkFormatsForDeletionRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = calibreBookUuid,
            formats = formats,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_markfmt_$putioFileId.json", jsonStr)
        val label = "Mark formats for deletion (${formats.joinToString(", ")})"
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = label,
            title = title ?: label,
            author = author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: CONFIRM_DELETE_BOOK
    suspend fun sendConfirmDeleteBookRequest(calibreBookUuid: String, googleAccount: String, title: String? = null, author: String? = null) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = ConfirmDeleteBookRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_confdel_$putioFileId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Delete book from library",
            title = title ?: "Delete book from library",
            author = author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: CONFIRM_DELETE_FORMATS
    suspend fun sendConfirmDeleteFormatsRequest(
        calibreBookUuid: String,
        formats: List<String>,
        googleAccount: String,
        title: String? = null,
        author: String? = null,
    ) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = ConfirmDeleteFormatsRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = calibreBookUuid,
            formats = formats,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_conffmt_$putioFileId.json", jsonStr)
        val label = "Delete formats from library (${formats.joinToString(", ")})"
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = label,
            title = title ?: label,
            author = author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    // CONTRACT: CANCEL_DELETION
    suspend fun sendCancelDeletionRequest(calibreBookUuid: String, googleAccount: String, title: String? = null, author: String? = null) {
        val putioFileId = -System.currentTimeMillis()  // negative = fileless, daemon-serialized
        val appId = settingsRepository.getOrCreateAppId()
        val request = CancelDeletionRequest(
            putio_file_id = putioFileId,
            calibre_book_uuid = calibreBookUuid,
            app_id = appId,
        )
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_canceldel_$putioFileId.json", jsonStr)
        val transfer = CalibreTransferEntity(
            putioFileId = putioFileId,
            fileName = "Cancel deletion",
            title = title ?: "Cancel deletion",
            author = author ?: "",
            status = if (gDriveId != null) CalibreTransferStatus.REQUESTED else CalibreTransferStatus.FAILED,
            addedAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            allPutioFileIds = putioFileId.toString(),
            gdriveRequestId = gDriveId,
            errorMessage = if (gDriveId == null) "Failed to upload to GDrive" else null,
            batchData = "[]",
            calibreBookUuid = calibreBookUuid,
            lastRequestPayload = jsonStr,
            hasPutioFile = false,
        )
        withContext(NonCancellable) { calibreTransferDao.insertTransfer(transfer) }
    }

    suspend fun sendProbeRequest(fileId: Long, googleAccount: String): Boolean {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return false

        // If we have a GDrive request ID, check if it still exists
        transfer.gdriveRequestId?.let { requestId ->
            if (gDriveManager.checkFileExists(googleAccount, requestId)) {
                // File still exists, daemon hasn't picked it up. Just update timestamp to wait longer.
                calibreTransferDao.updateTransfer(transfer.copy(
                    lastUpdatedAt = System.currentTimeMillis()
                ))
                return false
            }
        }
        
        val items = if (!transfer.batchData.isNullOrBlank()) {
            val decoded = try {
                json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData)
            } catch (e: Exception) {
                // Unlike the callers above, nothing catches around sendProbeRequest() in most of
                // its call sites (verifyCompletedTransfers' batch loop, the stuck-transfer
                // watchdog) — letting this throw would silently kill whichever loop called it
                // for every transfer after this one, not just fail this single probe.
                android.util.Log.e("CalibreRepository",
                    "sendProbeRequest: could not decode batchData for putio_file_id=$fileId", e)
                return false
            }
            decoded.map { item ->
                when (item.type) {
                    "PACK" -> item.copy(files = item.files?.map { it.copy(download_url = null) })
                    else -> item.copy(download_url = null)
                }
            }
        } else {
            val ids = transfer.parsedFileIds()
            if (ids.size > 1) {
                val audioFiles = ids.map { id -> AudiobookFile(id, "PROBE", null) }
                listOf(CalibreBatchItem("PACK", transfer.putioFileId, transfer.fileName, files = audioFiles))
            } else {
                listOf(CalibreBatchItem(
                    if (MetadataUtils.isArchive(transfer.fileName)) "ARCHIVE" else "SINGLE",
                    transfer.putioFileId,
                    transfer.fileName,
                    null
                ))
            }
        }

        val originalRequest = transfer.lastRequestPayload?.let {
            try { json.decodeFromString<CalibreBatchRequest>(it) } catch (e: Exception) { null }
        }

        val appId = settingsRepository.getOrCreateAppId()
        val request = CalibreBatchRequest(
            action = originalRequest?.action ?: "ADD_BOOK_BATCH",
            putio_file_id = transfer.putioFileId,
            title = transfer.title,
            author = transfer.author,
            items = items,
            is_probe = true,
            calibre_book_id = originalRequest?.calibre_book_id,
            calibre_book_uuid = transfer.calibreBookUuid,
            source_format = originalRequest?.source_format,
            target_format = originalRequest?.target_format,
            app_id = appId,
        )

        // Mark this attempt in-flight BEFORE submitting so pollResponses can recognize the
        // first matching response, however it arrives (LAN or Drive — see probesAwaitingResponse).
        probesAwaitingResponse.add(transfer.putioFileId)
        val jsonStr = json.encodeToString(request)
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_probe_${transfer.putioFileId}.json", jsonStr)
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                lastUpdatedAt = System.currentTimeMillis()
            ))
            return true
        }
        probesAwaitingResponse.remove(transfer.putioFileId)
        return false
    }

    // CONTRACT: stub convention — new-format stubs get a fresh put.io ID every time the
    // daemon re-syncs the file, so item.putio_file_id (the ORIGINAL file's ID, embedded in
    // the stub filename) can't be used directly to fetch the stub's JSON body — it has to be
    // located first via search, same as deleteFileFromPutio() does for deletion.
    private suspend fun resolveStubLocalPathByOriginalId(originalId: Long): String? {
        val tag = "CalibreRepository"
        // Cheap first attempt: covers old-format stubs, where the ID never drifts and
        // originalId IS the current live ID.
        readStubLocalPathById(originalId)?.let {
            android.util.Log.d(tag, "resolveStubLocalPath($originalId): resolved directly (no drift)")
            return it
        }

        val token = secureStorage.authTokenFlow.value
        if (token.isBlank()) {
            android.util.Log.w(tag, "resolveStubLocalPath($originalId): no put.io token available")
            return null
        }
        // searchFiles() does blocking OkHttp I/O synchronously — must be off the caller's
        // dispatcher (retryTransfer runs on Main via viewModelScope.launch) or it throws
        // NetworkOnMainThreadException, which has a null message and gets reported here as
        // the unhelpful "Unknown error".
        val searchResult = withContext(Dispatchers.IO) {
            withPutioRetry { putioApiClient.searchFiles(token, ".sk_synced.$originalId") }
        }
        if (searchResult !is NetworkResult.Success) {
            android.util.Log.w(tag, "resolveStubLocalPath($originalId): search failed — ${(searchResult as? NetworkResult.Error)?.message}")
            return null
        }
        android.util.Log.d(tag, "resolveStubLocalPath($originalId): search returned ${searchResult.data.size} result(s): ${searchResult.data.map { it.id to it.name }}")
        val stubId = searchResult.data
            .firstOrNull { it.name.substringAfterLast(".sk_synced.", "").toLongOrNull() == originalId }
            ?.id
        if (stubId == null) {
            android.util.Log.w(tag, "resolveStubLocalPath($originalId): no search result matched the embedded original ID")
            return null
        }
        val localPath = readStubLocalPathById(stubId)
        if (localPath == null) {
            android.util.Log.w(tag, "resolveStubLocalPath($originalId): found stub id=$stubId but its JSON body had no local_path (or fetch failed)")
        }
        return localPath
    }

    // A use_local item with no local_path can never be resolved by the daemon (the legacy
    // sync-index fallback is dead — see CONTRACTS.md §15), so resubmitting it verbatim just
    // reproduces the same permanent failure. Attempt to fill in the missing path from the
    // put.io stub before resending; returns null if any item is still unresolved.
    private suspend fun resolveMissingLocalPaths(items: List<CalibreBatchItem>): List<CalibreBatchItem>? {
        if (items.none { it.use_local == true && it.local_path == null }) return items
        val resolved = items.map { item ->
            if (item.use_local == true && item.local_path == null) {
                item.copy(local_path = resolveStubLocalPathByOriginalId(item.putio_file_id))
            } else item
        }
        return if (resolved.any { it.use_local == true && it.local_path == null }) null else resolved
    }

    suspend fun retryTransfer(fileId: Long, googleAccount: String): NetworkResult<Unit> {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return NetworkResult.Error("Transfer not found")

        if (transfer.transferType == "PLEX") return retryPlexTransfer(transfer, googleAccount)
        if (transfer.transferType == "PLEXAMP") return retryPlexampTransfer(transfer, googleAccount)

        // Resolve any missing local_path first — it mirrors whatever payload (stored or
        // reconstructed) is about to be sent below, so both branches pick up the fix.
        val storedItems = transfer.batchData?.let {
            try { json.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        }
        val resolvedStoredItems = storedItems?.let { resolveMissingLocalPaths(it) }
        if (storedItems != null && resolvedStoredItems == null) {
            calibreTransferDao.updateTransfer(transfer.copy(lastUpdatedAt = System.currentTimeMillis()))
            return NetworkResult.Error("File not confirmed synced yet — will retry automatically")
        }
        if (resolvedStoredItems != null && resolvedStoredItems != storedItems) {
            calibreTransferDao.updateTransfer(transfer.copy(batchData = json.encodeToString(resolvedStoredItems)))
        }

        val payload = transfer.lastRequestPayload?.let { stored ->
            if (resolvedStoredItems == null) stored else try {
                val req = json.decodeFromString<CalibreBatchRequest>(stored)
                json.encodeToString(req.copy(items = resolvedStoredItems))
            } catch (_: Exception) { stored }
        } ?: run {
            // Reconstruct if missing (for legacy transfers created before lastRequestPayload was added)
            val items = try {
                resolvedStoredItems ?: if (!transfer.batchData.isNullOrBlank()) {
                    json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData)
                } else {
                    // Fallback for very old single-file transfers
                    listOf(CalibreBatchItem(
                        type = if (MetadataUtils.isArchive(transfer.fileName)) "ARCHIVE" else "SINGLE",
                        putio_file_id = transfer.putioFileId,
                        fileName = transfer.fileName,
                        download_url = null // Note: retry might fail if daemon hasn't downloaded it yet and URL is null
                    ))
                }
            } catch (e: Exception) {
                return NetworkResult.Error("Could not reconstruct request: ${e.message}")
            }

            val action = when {
                transfer.fileName.startsWith("Update comments for") -> "UPDATE_COMMENTS"
                transfer.title.startsWith("Cover for") -> "REPLACE_COVER"
                else -> "ADD_BOOK_BATCH"
            }

            if (action == "UPDATE_COMMENTS") {
                return NetworkResult.Error("Original comment data was not saved and cannot be reconstructed")
            }

            val appId = settingsRepository.getOrCreateAppId()
            val request = CalibreBatchRequest(
                action = action,
                putio_file_id = transfer.putioFileId,
                title = transfer.title,
                author = transfer.author,
                items = items,
                calibre_book_uuid = transfer.calibreBookUuid,
                calibre_book_id = transfer.calibreBookId?.toLong(),
                app_id = appId,
                tags = transfer.tags,
                comments = transfer.comments,
                keep_cover = if (transfer.ignoreCover) true else null,
            )
            json.encodeToString(request)
        }

        // If calibreBookId was saved from a previous COMPLETED response but isn't in the stored
        // payload yet (legacy requests), inject it so the daemon can skip string matching.
        // Also inject tags if the stored payload predates that field.
        //
        // NOTE: title/author/calibre_book_uuid/calibre_book_id edits made via
        // updateFailedTransferAndRetry are applied by patching (and re-saving) lastRequestPayload
        // directly, before this function is ever called — not here. This block only fills in
        // gaps for *missing* fields, using the entity's value as a fallback default; it must
        // never overwrite a payload field with a null entity value, since several other request
        // types (e.g. sendUpdateCommentsRequest) set calibre_book_id directly on the payload at
        // creation without ever mirroring it onto CalibreTransferEntity.calibreBookId (that field
        // is otherwise only populated echoing back a COMPLETED ADD_BOOK_BATCH response).
        val finalPayload = run {
            var current = payload
            try {
                val req = json.decodeFromString<CalibreBatchRequest>(current)
                var updated = req
                if (transfer.calibreBookId != null && req.calibre_book_id == null)
                    updated = updated.copy(calibre_book_id = transfer.calibreBookId.toLong())
                if (transfer.tags != null && req.tags == null)
                    updated = updated.copy(tags = transfer.tags)
                // Comments have replace (not merge) semantics — unlike tags above, the entity's
                // value always wins over whatever the stored payload was last built with, so an
                // edit-assembly comments change reliably takes effect on the next send.
                if (transfer.comments != null && transfer.comments != req.comments)
                    updated = updated.copy(comments = transfer.comments)
                // A clipboard cover is already staged for this assembly (attachClipboardCoverToAssembly)
                // and will be applied via REPLACE_COVER once this completes — tell the daemon to skip
                // generating its default obfuscated cover for the protected item(s) in this request,
                // since it would just be immediately overwritten.
                if (transfer.pendingCoverPutioFileId != null && req.action == "ADD_BOOK_BATCH" &&
                    req.keep_cover != true && updated.items.any { it.protected == true }
                ) {
                    updated = updated.copy(keep_cover = true)
                }
                if (updated !== req) current = json.encodeToString(updated)
            } catch (_: Exception) {}
            current
        }

        android.util.Log.d("CalibreRepository", "Retrying transfer $fileId for $googleAccount")
        val gDriveId = daemonTransport.submitRequest(googleAccount,"req_${transfer.putioFileId}.json", finalPayload, isPriority = transfer.priority)
        
        if (gDriveId != null) {
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
                retryCount = if (transfer.status == CalibreTransferStatus.FAILED) transfer.retryCount + 1 else transfer.retryCount,
                errorMessage = null,
                lastRequestPayload = finalPayload,
            ))
            return NetworkResult.Success(Unit)
        }
        return NetworkResult.Error("Could not upload request to Google Drive")
    }

    // Manual escape hatch for FAILED ADD_BOOK_BATCH transfers stuck because calibredb's own
    // fuzzy title/author duplicate matching silently no-ops (calibredb add --automerge=ignore)
    // while this app's/the daemon's own title+author matching misses the same book — e.g. a
    // leading article or a hyphen-vs-colon difference in the title. Lets the user manually pin
    // the correct target book by uuid/id, or edit the title enough that it no longer resembles
    // an existing one, then resend. Patches lastRequestPayload directly with an unconditional
    // overwrite (these are exactly the fields the user just edited) rather than relying on
    // retryTransfer's own patch step, which must stay conservative for its other callers — see
    // the NOTE above that block.
    suspend fun updateFailedTransferAndRetry(
        fileId: Long,
        title: String,
        author: String,
        calibreBookUuid: String?,
        calibreBookId: Long?,
        googleAccount: String,
    ): NetworkResult<Unit> {
        val transfer = calibreTransferDao.getTransferById(fileId)
            ?: return NetworkResult.Error("Transfer not found")
        if (transfer.status != CalibreTransferStatus.FAILED)
            return NetworkResult.Error("Transfer is not in a failed state")

        val patchedPayload = transfer.lastRequestPayload?.let { stored ->
            try {
                val req = json.decodeFromString<CalibreBatchRequest>(stored)
                json.encodeToString(req.copy(
                    title = title,
                    author = author,
                    calibre_book_uuid = calibreBookUuid,
                    calibre_book_id = calibreBookId,
                ))
            } catch (_: Exception) { stored }
        }

        calibreTransferDao.updateTransfer(transfer.copy(
            title = title,
            author = author,
            calibreBookUuid = calibreBookUuid,
            calibreBookId = calibreBookId?.toInt(),
            lastRequestPayload = patchedPayload ?: transfer.lastRequestPayload,
            lastUpdatedAt = System.currentTimeMillis(),
        ))

        return retryTransfer(fileId, googleAccount)
    }

    fun observeChainedTransfers(): Flow<List<CalibreTransferEntity>> = calibreTransferDao.observeChainedTransfers()
    fun observeChainedCount(): Flow<Int> = calibreTransferDao.observeChainedCount()

    // CONTRACT: CHAIN
    suspend fun reorderChain(orderedFileIds: List<Long>) {
        orderedFileIds.forEachIndexed { index, fileId ->
            val transfer = calibreTransferDao.getTransferById(fileId) ?: return@forEachIndexed
            if (transfer.status != CalibreTransferStatus.CHAINED) return@forEachIndexed
            calibreTransferDao.updateTransfer(transfer.copy(chainPosition = index))
        }
    }

    // CONTRACT: CHAIN — sends every staged (CHAINED) item at once, wrapped in one CHAIN
    // request, in their current chainPosition order. Every CHAINED row is guaranteed to carry
    // a complete lastRequestPayload (populated at staging time by addTransfer/addMergeTransfer/
    // the send*Request functions' dispatchOrStage path) — unlike retryTransfer, there is no
    // reconstruct-from-batchData fallback here, so a chain member is only ever unplacable if
    // something upstream failed to set lastRequestPayload, which would be a bug, not a normal
    // runtime condition.
    suspend fun placeChain(googleAccount: String): NetworkResult<Unit> {
        val chained = calibreTransferDao.observeChainedTransfers().first()
        if (chained.isEmpty()) return NetworkResult.Error("Chain is empty")

        val items = mutableListOf<kotlinx.serialization.json.JsonElement>()
        for (transfer in chained) {
            val payload = transfer.lastRequestPayload
                ?: return NetworkResult.Error("\"${transfer.title}\" has no saved request and can't be placed")
            try {
                items.add(json.parseToJsonElement(payload))
            } catch (e: Exception) {
                return NetworkResult.Error("\"${transfer.title}\"'s saved request is unreadable: ${e.message}")
            }
        }

        val chainPutioId = -System.currentTimeMillis()
        val appId = settingsRepository.getOrCreateAppId()
        val chainRequest = buildJsonObject {
            put("action", "CHAIN")
            put("putio_file_id", chainPutioId)
            put("app_id", appId)
            put("items", JsonArray(items))
        }
        val jsonStr = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), chainRequest)
        // CONTRACT: CHAIN — the daemon's Drive-path dispatch classifier recognizes a chain
        // request by this `req_chain_` filename prefix (in addition to the parsed action, which
        // the LAN path already has); see _dispatch_or_queue in putz_manager.py.
        val gDriveId = daemonTransport.submitRequest(googleAccount, "req_chain_$chainPutioId.json", jsonStr)
            ?: return NetworkResult.Error("Could not upload chain request to Google Drive")

        val chainIdStr = chainPutioId.toString()
        chained.forEach { transfer ->
            calibreTransferDao.updateTransfer(transfer.copy(
                status = CalibreTransferStatus.REQUESTED,
                chainId = chainIdStr,
                gdriveRequestId = gDriveId,
                lastUpdatedAt = System.currentTimeMillis(),
            ))
        }
        return NetworkResult.Success(Unit)
    }

    // CONTRACT: CHAIN — there's no "undispatched, not-in-a-chain" state to fall back to, so
    // removing an item from the chain-in-progress just sends it standalone right away, exactly
    // as "Send now" would have at staging time — reuses retryTransfer's reconstruct-and-resubmit
    // path, which already works unmodified for any row carrying a full lastRequestPayload.
    suspend fun removeFromChain(fileId: Long, googleAccount: String): NetworkResult<Unit> =
        retryTransfer(fileId, googleAccount)

    suspend fun getTransfer(fileId: Long): CalibreTransferEntity? {
        return calibreTransferDao.getTransferById(fileId)
    }

    // CONTRACT: priority requests lane — promotes a not-yet-claimed transfer in place. A no-op
    // (returns false, leaves the local row untouched) once the daemon has already claimed the
    // request (PROCESSING) or finished it (COMPLETED/FAILED) — there's nothing left to promote,
    // and showing a "priority" badge on a transfer that's already past the point it could matter
    // would just be misleading.
    suspend fun promoteTransferToPriority(fileId: Long, googleAccount: String): Boolean {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return false
        if (transfer.priority) return true
        if (transfer.status != CalibreTransferStatus.PENDING &&
            transfer.status != CalibreTransferStatus.UPLOADING &&
            transfer.status != CalibreTransferStatus.ASSEMBLED &&
            transfer.status != CalibreTransferStatus.REQUESTED
        ) return false

        // Only REQUESTED has already reached Drive — earlier states (PENDING/UPLOADING/ASSEMBLED)
        // haven't dispatched yet, so setting the local flag is enough; the eventual dispatch
        // (resolveLocalPathAndDispatch/updateTransferAfterUpload/updateMergeAfterUpload) reads
        // transfer.priority and routes into the priority folder itself.
        if (transfer.status == CalibreTransferStatus.REQUESTED) {
            val requestId = transfer.gdriveRequestId ?: return false
            val moved = daemonTransport.promoteRequestToPriority(googleAccount, requestId)
            if (!moved) return false
        }

        calibreTransferDao.updateTransfer(transfer.copy(priority = true))
        return true
    }

    suspend fun removeTransfer(fileId: Long) {
        // Clear the Drive request too — otherwise a request the daemon hasn't picked up yet
        // survives on Drive after its local record is gone, and the daemon (which only looks
        // at Drive) will still pick it up and process it, e.g. on its next restart/poll.
        //
        // Only attempted for a non-terminal transfer, though: COMPLETED/FAILED are states the
        // daemon only ever reaches after it has already trashed its own request file as part of
        // normal processing (see putz_manager.py's process_book_batch — the request is trashed
        // in a finally block on every path except a retry-needed one, which never reaches these
        // statuses). So for a terminal transfer this call almost always just hits a 404 (see
        // GDriveManager.deleteFile's "already gone" handling) while still paying a full network
        // round-trip — the actual reason batch-clearing verified transfers was slow even with
        // "also delete from put.io" unchecked, since that flag only gates the put.io API calls,
        // not this one.
        val transfer = calibreTransferDao.getTransferById(fileId)
        val isTerminal = transfer?.status == CalibreTransferStatus.COMPLETED || transfer?.status == CalibreTransferStatus.FAILED
        if (!isTerminal) {
            transfer?.gdriveRequestId?.let { requestId ->
                val account = settingsRepository.googleTokenFlow.first()
                if (account.isNotBlank()) {
                    gDriveManager.deleteFile(account, requestId)
                }
            }
        }
        calibreTransferDao.deleteTransfer(fileId)
    }

    suspend fun markPackUploadFailed(fileId: Long, errorMessage: String) {
        val transfer = calibreTransferDao.getTransferById(fileId) ?: return
        calibreTransferDao.updateTransfer(transfer.copy(
            status = CalibreTransferStatus.FAILED,
            errorMessage = errorMessage,
            lastUpdatedAt = System.currentTimeMillis(),
        ))
    }

    private val putioRetryableCodes = setOf(429, 500, 502, 503, 504)

    /** Retries a put.io call with exponential backoff on rate-limit/server errors. */
    private suspend fun <T> withPutioRetry(maxAttempts: Int = 5, call: () -> NetworkResult<T>): NetworkResult<T> {
        var delayMs = 5_000L
        var lastResult: NetworkResult<T>
        for (attempt in 1..maxAttempts) {
            lastResult = call()
            if (lastResult !is NetworkResult.Error || lastResult.code !in putioRetryableCodes || attempt == maxAttempts) {
                return lastResult
            }
            delay(delayMs)
            delayMs = minOf(delayMs * 2, 60_000L)
        }
        error("unreachable")
    }

    suspend fun deleteFileFromPutio(token: String, fileId: Long): NetworkResult<Unit> {
        return withContext(Dispatchers.IO) {
            val transfer = calibreTransferDao.getTransferById(fileId)
            val originalIds = transfer?.parsedFileIds() ?: listOf(fileId)
            val idsToDelete = mutableListOf<Long>()
            // Source IDs whose put.io state we couldn't confirm this round (transient lookup
            // failure or a failed batch delete) — kept around so a retry only targets these
            // instead of redoing the whole pack, and so a single bad file can't wipe out
            // deletions already resolved for the rest of a multi-file pack.
            val unresolvedIds = mutableListOf<Long>()
            for ((index, originalId) in originalIds.withIndex()) {
                if (originalIds.size > 1) {
                    updateDeleteProgress(TransferDeleteProgress(
                        message = "Resolving file ${index + 1}/${originalIds.size}",
                        current = index + 1,
                        total = originalIds.size,
                    ))
                }
                // CONTRACT: stub convention — new-format stubs have a different put.io ID than the original.
                // Search for the stub by its filename suffix; if found, delete the stub (not the original).
                val searchResult = withPutioRetry { putioApiClient.searchFiles(token, ".sk_synced.$originalId") }
                val stubIds = (searchResult as? NetworkResult.Success)?.data
                    ?.filter { it.name.substringAfterLast(".sk_synced.", "").toLongOrNull() == originalId }
                    ?.map { it.id } ?: emptyList()
                if (stubIds.isNotEmpty()) {
                    idsToDelete.addAll(stubIds)
                } else {
                    // Stub not found via search (or the search call itself failed after retries —
                    // don't let one flaky search abort deletion of the rest of a multi-file pack).
                    // Verify whether the original file still exists:
                    // - 200: old-format transfer (never replaced by a stub) → delete the original.
                    // - 404: file was synced to a stub, but search didn't find it (transient search-index
                    //        lag or a non-retried search failure). Adding originalId would silently no-op
                    //        (put.io accepts deletes of non-existent IDs), leaving the stub on put.io —
                    //        so treat it as unresolved and retry later instead of dropping it.
                    // - other error: network issue → leave this file for a retry rather than risk
                    //        deleting the wrong file, but keep resolving the rest of the pack.
                    val fileCheck = withPutioRetry { putioApiClient.getFile(token, originalId) }
                    when {
                        fileCheck is NetworkResult.Success ->
                            idsToDelete.add(originalId)
                        (fileCheck as? NetworkResult.Error)?.code == 404 -> {
                            android.util.Log.w("CalibreRepository",
                                "Stub for put.io file $originalId not found via search and original is gone — will retry search later")
                            unresolvedIds.add(originalId)
                        }
                        else -> {
                            android.util.Log.w("CalibreRepository",
                                "Could not verify put.io file $originalId — will retry later: ${(fileCheck as? NetworkResult.Error)?.message}")
                            unresolvedIds.add(originalId)
                        }
                    }
                }
            }
            if (idsToDelete.isNotEmpty()) {
                val result = withPutioRetry { putioApiClient.deleteFiles(token, idsToDelete.distinct()) }
                if (result is NetworkResult.Error) {
                    android.util.Log.w("CalibreRepository",
                        "Failed to delete put.io files $idsToDelete: ${result.message}")
                    unresolvedIds.addAll(idsToDelete)
                }
            }
            if (unresolvedIds.isEmpty()) return@withContext NetworkResult.Success(Unit)

            // Narrow the transfer's tracked IDs to just what's left so a retry doesn't
            // re-resolve files that were already confirmed deleted in this pass.
            if (transfer != null) {
                calibreTransferDao.updateTransfer(transfer.copy(
                    allPutioFileIds = unresolvedIds.distinct().joinToString(","),
                ))
            }
            NetworkResult.Error("Could not delete ${unresolvedIds.distinct().size} of ${originalIds.size} file(s) from put.io")
        }
    }

    suspend fun restartOrphanedUpload(transfer: CalibreTransferEntity) {
        val token = secureStorage.authTokenFlow.value
        val googleAccount = settingsRepository.googleTokenFlow.first()
        if (token.isBlank() || googleAccount.isBlank()) return

        val localUris: List<String> = transfer.localUrisJson?.let {
            try { json.decodeFromString(it) } catch (e: Exception) { null }
        } ?: transfer.sourceLocalUri?.let { listOf(it) } ?: return

        // Derive file names from batchData (PACK) or the single fileName
        val fileNames: List<String> = if (localUris.size == 1) {
            listOf(transfer.fileName)
        } else {
            try {
                val items = json.decodeFromString<List<CalibreBatchItem>>(transfer.batchData ?: "")
                items.firstOrNull()?.files?.map { it.fileName } ?: return
            } catch (e: Exception) { return }
        }
        if (localUris.size != fileNames.size) return

        // Mark as restarting immediately so the polling loop doesn't launch a second restart
        updateUploadProgress(transfer.putioFileId, "Restarting…")

        // Find or create .putz_attachments
        val rootFiles = withContext(Dispatchers.IO) {
            (putioApiClient.listFiles(token, 0) as? NetworkResult.Success)?.data?.first ?: emptyList()
        }
        var folderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id
        if (folderId == null) {
            val result = withContext(Dispatchers.IO) { putioApiClient.createFolder(token, 0, ".putz_attachments") }
            folderId = (result as? NetworkResult.Success)?.data?.id
        }
        if (folderId == null) {
            updateUploadProgress(transfer.putioFileId, null)
            return
        }

        val uploadedFiles = mutableListOf<Triple<Long, String, String>>()
        val total = localUris.size

        for ((index, uriStr) in localUris.withIndex()) {
            val uri = android.net.Uri.parse(uriStr)
            val name = fileNames[index]

            // Dedup: skip upload if a file with the same name and size already exists
            val localSize = withContext(Dispatchers.IO) {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }
            if (localSize > 0) {
                val folderFiles = withContext(Dispatchers.IO) {
                    (putioApiClient.listFiles(token, folderId) as? NetworkResult.Success)?.data?.first ?: emptyList()
                }
                val existing = folderFiles.find { it.name == name && it.size == localSize }
                if (existing != null) {
                    val url = "${com.damarquez.putz.data.remote.PutioApiClient.BASE_URL}/files/${existing.id}/download?oauth_token=$token"
                    uploadedFiles.add(Triple(existing.id, url, name))
                    updateUploadProgress(transfer.putioFileId, "${index + 1}/$total · cached")
                    continue
                }
            }

            // Upload with timeout + exponential-backoff retry
            val retryableCodes = setOf(429, 500, 502, 503, 504)
            val maxAttempts = 5
            var delayMs = 5_000L
            var succeeded = false
            for (attempt in 1..maxAttempts) {
                val result = try {
                    withTimeout(3 * 60 * 1000L) {
                        putioApiClient.uploadFile(token, folderId, name, uri, context.contentResolver) { bytesWritten, totalBytes ->
                            val pct = if (totalBytes > 0) (bytesWritten * 100 / totalBytes).toInt() else 0
                            updateUploadProgress(transfer.putioFileId, "${index + 1}/$total · $pct%")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    NetworkResult.Error("Upload timed out", null)
                } catch (e: Exception) {
                    NetworkResult.Error(e.message ?: "Error", null)
                }

                val errorCode = (result as? NetworkResult.Error)?.code
                when {
                    result is NetworkResult.Success -> {
                        val url = "${com.damarquez.putz.data.remote.PutioApiClient.BASE_URL}/files/${result.data.id}/download?oauth_token=$token"
                        uploadedFiles.add(Triple(result.data.id, url, name))
                        succeeded = true
                        break
                    }
                    attempt < maxAttempts && (errorCode in retryableCodes || errorCode == null) -> {
                        delay(delayMs)
                        delayMs = minOf(delayMs * 2, 60_000L)
                    }
                    else -> break
                }
            }

            if (!succeeded) {
                updateUploadProgress(transfer.putioFileId, null)
                calibreTransferDao.updateTransfer(transfer.copy(
                    status = CalibreTransferStatus.FAILED,
                    errorMessage = "Upload failed on restart",
                    lastUpdatedAt = System.currentTimeMillis(),
                ))
                return
            }
        }

        updateUploadProgress(transfer.putioFileId, null)
        if (localUris.size == 1) {
            val (uploadedId, downloadUrl, _) = uploadedFiles.first()
            updateTransferAfterUpload(transfer.putioFileId, uploadedId, downloadUrl, googleAccount)
        } else {
            val resolvedFiles = uploadedFiles.map { (id, url, name) -> AudiobookFile(id, name, url) }
            updateMergeAfterUpload(transfer.putioFileId, resolvedFiles, googleAccount)
        }
    }
}
