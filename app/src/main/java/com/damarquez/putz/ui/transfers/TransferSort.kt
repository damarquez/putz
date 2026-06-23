package com.damarquez.putz.ui.transfers

import com.damarquez.putz.data.model.MergedTransfer
import java.time.Instant

enum class TransferSortField(val label: String) {
    NAME("Name"),
    DATE("Date"),
    SIZE("Size"),
}

/** field == null means "default" — keep the API/grouping order untouched. */
data class TransferSortSpec(
    val field: TransferSortField? = null,
    val ascending: Boolean = true,
)

private fun MergedTransfer.sortDateMillis(): Long =
    transfer.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: historyEntry?.addedAt?.times(1000L)
        ?: 0L

fun sortTransfers(transfers: List<MergedTransfer>, spec: TransferSortSpec): List<MergedTransfer> {
    val comparator: Comparator<MergedTransfer> = when (spec.field) {
        null -> return transfers
        TransferSortField.NAME -> compareBy { it.appDisplayName.lowercase() }
        TransferSortField.DATE -> compareBy { it.sortDateMillis() }
        TransferSortField.SIZE -> compareBy { it.transfer.size }
    }
    return transfers.sortedWith(if (spec.ascending) comparator else comparator.reversed())
}
