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

    /** The exact JSON payload last uploaded to GDrive for this transfer. Used verbatim on retry. */
    val lastRequestPayload: String? = null,

    /** JSON array of local SAF URIs (in file order) for UPLOADING transfers, so the upload
     *  can be restarted if the app is killed mid-upload. */
    val localUrisJson: String? = null,
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
