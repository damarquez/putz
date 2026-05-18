package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PutioFile(
    val id: Long,
    val name: String,
    @SerialName("file_type") val fileType: String = "OTHER",
    @SerialName("content_type") val contentType: String? = null,
    val size: Long = 0L,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("parent_id") val parentId: Long = -1L,
    @SerialName("is_shared") val isShared: Boolean = false,
    val icon: String? = null,
    val screenshot: String? = null,
    @SerialName("is_mp4_available") val isMp4Available: Boolean = false,

    // Local file integration
    val isLocal: Boolean = false,
    val localUri: String? = null,

    // LAN file integration
    val isLan: Boolean = false,
    val lanPath: String? = null,
    val lanConnectionId: Long? = null,
) {
    val isFolder: Boolean get() = fileType == "FOLDER"
}

enum class PutioFileType(val apiValue: String) {
    FOLDER("FOLDER"),
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    IMAGE("IMAGE"),
    ARCHIVE("ARCHIVE"),
    PDF("PDF"),
    OTHER("OTHER");

    companion object {
        fun from(value: String): PutioFileType =
            entries.firstOrNull { it.apiValue == value } ?: OTHER
    }
}
