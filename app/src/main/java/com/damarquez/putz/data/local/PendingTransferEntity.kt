package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A magnet/URL that put.io rejected with TRANSFER_ADD_LIMITED_REACHED. Held here until
 * the active-transfer count drops below put.io's limit, then resubmitted automatically.
 */
@Entity(tableName = "pending_transfers")
data class PendingTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val magnetOrUrl: String,
    val saveParentId: Long = 0L,
    val infoHash: String?,
    val displayNameHint: String?,
    val addedAt: Long,
)
