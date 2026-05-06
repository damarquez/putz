package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountInfo(
    val username: String,
    val mail: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("disk_quota") val diskQuota: Long = 0L,
    @SerialName("disk_used") val diskUsed: Long = 0L,
) {
    val diskUsedPercent: Float
        get() = if (diskQuota > 0) diskUsed.toFloat() / diskQuota.toFloat() else 0f
}
