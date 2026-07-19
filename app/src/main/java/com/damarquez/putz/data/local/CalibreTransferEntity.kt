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
    ASSEMBLED
}
