package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-chosen display name for a put.io folder, shown in Putz instead of the real put.io
 * name (used for chapter/bookmark labels in joins too) without ever renaming the folder on
 * put.io itself. Device-local, like the rest of Putz's local state.
 */
@Entity(tableName = "folder_display_names")
data class FolderDisplayNameEntity(
    @PrimaryKey val putioFileId: Long,
    val displayName: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
