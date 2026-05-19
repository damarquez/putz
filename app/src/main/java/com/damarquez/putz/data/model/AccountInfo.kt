package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountInfo(
    val username: String,
    val mail: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val disk: DiskInfo? = null,
) {
    @Serializable
    data class DiskInfo(
        val avail: Long = 0L,
        val used: Long = 0L,
        val size: Long = 0L,
    )

    val diskQuota: Long get() = disk?.size ?: 0L
    val diskUsed: Long get() = disk?.used ?: 0L
    val diskAvail: Long get() = disk?.avail ?: 0L

    val diskUsedPercent: Float
        get() = if (diskQuota > 0) diskUsed.toFloat() / diskQuota.toFloat() else 0f
}
