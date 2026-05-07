package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

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
)

enum class CalibreTransferStatus {
    PENDING,
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED
}
