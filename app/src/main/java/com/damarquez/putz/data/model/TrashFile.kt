package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrashFile(
    val id: Long,
    val name: String,
    @SerialName("file_type") val fileType: String = "OTHER",
    @SerialName("content_type") val contentType: String? = null,
    val size: Long = 0L,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
    @SerialName("parent_id") val parentId: Long? = null,
) {
    val isFolder: Boolean get() = fileType == "FOLDER"
}
