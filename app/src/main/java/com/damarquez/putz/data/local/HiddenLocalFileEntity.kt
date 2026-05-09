package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores URIs of files inside attached folders that should be hidden (detached) from the UI.
 */
@Entity(tableName = "hidden_local_files")
data class HiddenLocalFileEntity(
    @PrimaryKey val uri: String,
    val parentAttachmentId: Long, // Link to the LocalAttachmentEntity folder
    val addedAt: Long = System.currentTimeMillis()
)
