package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HistoryFileEntry(
    @SerialName("info_hash") val infoHash: String,
    val label: String,
    val status: String,
    @SerialName("added_at") val addedAt: Long,
    @SerialName("magnet_uri") val magnetUri: String? = null,
    @SerialName("putio_id") val putioId: Long? = null,
    @SerialName("putio_name") val putioName: String? = null,
    @SerialName("resolved_name") val resolvedName: String? = null,
    @SerialName("total_size_bytes") val totalSizeBytes: Long? = null,
    val files: List<HistoryEntryFile>? = null,
)

@Serializable
data class HistoryEntryFile(
    val name: String,
    val size: Long = 0L,
)

@Serializable
data class TransferHistoryJson(
    val version: Int,
    @SerialName("updated_at") val updatedAt: Long,
    val entries: List<HistoryFileEntry>,
)
