package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_attachments")
data class LocalAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val parentId: Long, // Typically 0 for the virtual "Local Files" root
    val isFolder: Boolean,
    val addedAt: Long = System.currentTimeMillis()
)
