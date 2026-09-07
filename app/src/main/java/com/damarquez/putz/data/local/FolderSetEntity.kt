package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folder_set")
data class FolderSetEntity(
    @PrimaryKey val putioFileId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)
