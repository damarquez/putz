package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PutioTransfer(
    val id: Long,
    val name: String,
    val status: String = "WAITING",
    @SerialName("percent_done") val percentDone: Int = 0,
    val size: Long = 0L,
    val downloaded: Long = 0L,
    val uploaded: Long = 0L,
    @SerialName("current_ratio") val currentRatio: Float = 0f,
    @SerialName("up_speed") val upSpeed: Long = 0L,
    @SerialName("down_speed") val downSpeed: Long = 0L,
    val source: String? = null,
    @SerialName("file_id") val fileId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("estimated_time") val estimatedTime: Long? = null,
    @SerialName("peers_connected") val peersConnected: Int = 0,
    @SerialName("peers_getting_from_us") val peersGettingFromUs: Int = 0,
    @SerialName("peers_sending_to_us") val peersSendingToUs: Int = 0,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("save_parent_id") val saveParentId: Long = 0L,
    val hash: String? = null,
    @SerialName("is_private") val isPrivate: Boolean = false,
    val availability: Int = 0,
    @SerialName("tracker_message") val trackerMessage: String? = null,
)

enum class TransferStatus(val label: String) {
    WAITING("Waiting"),
    IN_QUEUE("In Queue"),
    PREPARING_DOWNLOAD("Preparing"),
    DOWNLOADING("Downloading"),
    COMPLETING("Completing"),
    SEEDING("Seeding"),
    COMPLETED("Completed"),
    ERROR("Error"),
    STOPPED("Stopped");

    companion object {
        fun from(value: String): TransferStatus =
            entries.firstOrNull { it.name == value } ?: WAITING
    }
}

enum class TransferGroup(val label: String) {
    ACTIVE("Active"),
    QUEUED("Queued"),
    COMPLETED("Completed"),
    FAILED("Error / Stopped"),
}

fun PutioTransfer.group(): TransferGroup = when (TransferStatus.from(status)) {
    TransferStatus.DOWNLOADING,
    TransferStatus.SEEDING,
    TransferStatus.COMPLETING,
    TransferStatus.PREPARING_DOWNLOAD -> TransferGroup.ACTIVE
    TransferStatus.WAITING,
    TransferStatus.IN_QUEUE -> TransferGroup.QUEUED
    TransferStatus.COMPLETED -> TransferGroup.COMPLETED
    TransferStatus.ERROR,
    TransferStatus.STOPPED -> TransferGroup.FAILED
}

fun PutioTransfer.isActive(): Boolean = group() == TransferGroup.ACTIVE
