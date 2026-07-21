package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Drive response file IDs Putz has already decided to delete (processed or orphaned) but whose
 * delete call failed or never got the chance to run (e.g. app backgrounded mid-poll). Persisting
 * this lets a later poll retry the bare delete without redownloading/reprocessing the file's
 * content, instead of the whole backlog being re-fetched from scratch every time.
 */
@Entity(tableName = "pending_response_deletions")
data class PendingResponseDeletionEntity(
    @PrimaryKey val fileId: String,
    val discoveredAt: Long = System.currentTimeMillis(),
)
