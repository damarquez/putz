package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lan_connections",
    indices = [Index(value = ["label"], unique = true)]
)
data class LanConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    val username: String,
    val shareName: String,
    val addedAt: Long = System.currentTimeMillis(),
)
