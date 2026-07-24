package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "calibre_transfers")
data class CalibreTransferEntity(
    @PrimaryKey val putioFileId: Long,
    val fileName: String,
    val title: String,
    val author: String,
    val status: CalibreTransferStatus,
    val addedAt: Long,
    val lastUpdatedAt: Long,
    val errorMessage: String? = null,
    val gdriveRequestId: String? = null,
    // Comma-separated list of all put.io file IDs; for single-file transfers this equals putioFileId
    val allPutioFileIds: String = "",
    val retryCount: Int = 0,

    /** True if the underlying put.io files were temporarily uploaded from local storage 
     *  and should be deleted upon successful transfer. */
    val isTempUpload: Boolean = false,

    /** The original local SAF URI of the file, if it was uploaded from local storage. 
     *  Used to detach the file from Putz when the transfer is removed. */
    val sourceLocalUri: String? = null,

    /** JSON representation of List<CalibreBatchItem> for assembled books. */
    val batchData: String? = null,

    /** The Calibre UUID of the book, if targeted directly. */
    val calibreBookUuid: String? = null,

    /** The Calibre integer book ID returned by the daemon on COMPLETED. Echoed back on retry/probe
     *  so the daemon can locate the book by ID instead of fallible title+author string matching. */
    val calibreBookId: Int? = null,

    /** The exact JSON payload last uploaded to GDrive for this transfer. Used verbatim on retry. */
    val lastRequestPayload: String? = null,

    /** JSON array of local SAF URIs (in file order) for UPLOADING transfers, so the upload
     *  can be restarted if the app is killed mid-upload. */
    val localUrisJson: String? = null,

    /** Newline-separated warnings reported by the daemon on a COMPLETED transfer. */
    val warnings: String? = null,

    /** Comma-separated tags to append to the book on the daemon side (always merged with existing). */
    val tags: String? = null,

    /** "CALIBRE" or "PLEX". Used to route dispatch and display logic. */
    val transferType: String = "CALIBRE",

    /** True once the book has been confirmed present in the synced Calibre library.
     *  COMPLETED transfers show yellow until this is set; then they turn green. */
    val libraryVerified: Boolean = false,

    /** Number of times the user has manually re-checked this COMPLETED transfer via
     *  "Check & refresh" (CalibreTransferItem.kt) — each check also re-verifies the
     *  book/formats with the daemon and refreshes its assets.db entry. */
    val probeCount: Int = 0,

    /** False for requests with no real put.io file behind them — e.g. PROTECT_BOOK,
     *  UNPROTECT_BOOK, SET_PAGE_COUNT, UPDATE_COMMENTS — which use a synthetic
     *  System.currentTimeMillis() as putioFileId. "Also delete from put.io" must be a
     *  no-op for these; there is nothing on put.io to delete. */
    val hasPutioFile: Boolean = true,

    /** True once this transfer's request has been placed (or promoted) into the daemon's
     *  priority lane (requests/priority/ — see CONTRACTS.md §17). Purely a display flag; the
     *  actual queue position is which Drive folder gdriveRequestId's file currently lives in. */
    val priority: Boolean = false,

    /** Seconds the daemon actually spent fulfilling this request, echoed on COMPLETED
     *  (CalibreResponse.duration_seconds) — from when a worker began processing it, not
     *  from when it first arrived/was queued. Null for transfers completed before the
     *  daemon started reporting this. */
    val durationSeconds: Double? = null,

    /** Set once this transfer is placed as part of a chain (see CalibreRepository.placeChain).
     *  Persists after placement (unlike [chainPosition]) so completed/failed rows can still
     *  show "was part of chain X". Null for a transfer never chained. */
    val chainId: String? = null,

    /** Ordering position while [status] is CHAINED (staged, not yet placed) — the request
     *  chain screen sorts by this. Meaningless once placed; left as-is rather than cleared,
     *  since it's harmless dead weight after that point. */
    val chainPosition: Int? = null,

    /** Put.io file id of a clipboard image already uploaded to `.putz_attachments`, staged as
     *  this not-yet-dispatched (ASSEMBLED/CHAINED) request's future cover. Null once consumed —
     *  see [pendingCoverDownloadUrl] and CalibreRepository.pollResponses' COMPLETED handling,
     *  which fires REPLACE_COVER automatically once this book's real calibre_book_uuid is known. */
    val pendingCoverPutioFileId: Long? = null,

    /** Download URL for [pendingCoverPutioFileId], stashed so REPLACE_COVER can be sent without
     *  re-uploading once this transfer completes. */
    val pendingCoverDownloadUrl: String? = null,

    /** Display filename for [pendingCoverPutioFileId]. */
    val pendingCoverFileName: String? = null,

    /** When true and this transfer's item(s) are [CalibreBatchItem.protected], the daemon skips
     *  generating its default obfuscated cover on ADD_BOOK_BATCH — the added book's cover behaves
     *  exactly like an unprotected book's (embedded/extracted cover, or none). Set from the
     *  "Send to Calibre" / "Edit assembly" dialogs' "Ignore random cover" toggle; has no effect
     *  when the item isn't protected. See CalibreBatchRequest.keep_cover. */
    val ignoreCover: Boolean = false,
    ) {

    fun parsedFileIds(): List<Long> = 

        if (allPutioFileIds.isNotEmpty())
            allPutioFileIds.split(",").mapNotNull { it.toLongOrNull() }
        else
            listOf(putioFileId)
}

@Serializable
enum class CalibreTransferStatus {
    UPLOADING,
    PENDING,
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    ASSEMBLED,

    /** Fully built request, staged into the chain-in-progress via "Add to chain" — has a
     *  complete [CalibreTransferEntity.lastRequestPayload] but has not been dispatched.
     *  See [CalibreTransferEntity.chainPosition] for ordering; CONTRACT: CHAIN. */
    CHAINED,
}
