package com.damarquez.putz.ui.transfers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.data.repository.CalibreBatchItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString

private val batchItemsJson = Json { ignoreUnknownKeys = true }
private val prettyJson = Json { prettyPrint = true }

// Shared with TransferBrowserSheet.kt so the format chip shown on the card and the
// format root node shown in the tree always agree on labeling.
internal fun formatLabelFor(item: CalibreBatchItem): String = when (item.type) {
    "PACK" -> "M4B"
    else -> item.fileName.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
        ?.uppercase() ?: item.type
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalibreTransferItem(
    transfer: CalibreTransferEntity,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
    onRetry: () -> Unit,
    onCopyJson: (String) -> Unit,
    modifier: Modifier = Modifier,
    uploadProgress: String? = null,
    isPendingAppend: Boolean = false,
    onCopyUuid: ((String) -> Unit)? = null,
    onCopyTitle: ((String) -> Unit)? = null,
    onCopyAuthor: ((String) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onMakePriority: (() -> Unit)? = null,
    onRemoveFromChain: (() -> Unit)? = null,
    onOpenChain: (() -> Unit)? = null,
    onSetCoverFromClipboard: (() -> Unit)? = null,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var showContextMenu by remember { mutableStateOf(false) }
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(transfer.addedAt))
    val formatLabels: List<String> = remember(transfer.batchData, transfer.fileName, transfer.lastRequestPayload) {
        val items = transfer.batchData?.let {
            try { batchItemsJson.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        }
        if (!items.isNullOrEmpty()) {
            items.map { item -> formatLabelFor(item) }.distinct()
        } else {
            val actionLabel = transfer.lastRequestPayload?.let { payload ->
                try {
                    when (batchItemsJson.parseToJsonElement(payload).jsonObject["action"]?.jsonPrimitive?.content) {
                        "BATCH_ADD_TAGS" -> "TAGS"
                        "UPDATE_COMMENTS" -> "META"
                        "GENERATE_COVER" -> "GENC"
                        "REPLACE_COVER" -> "RPLC"
                        "SET_PAGE_COUNT" -> "PGS"
                        "CONFIRM_DELETE_BOOK", "CONFIRM_DELETE_FORMATS" -> "DEL"
                        "FUSE_BOOKS" -> "FUSE"
                        "MANAGE_VIRTUAL_LIBRARY" -> "VLIB"
                        "PRIORITY_PUTIO_SYNC" -> "SYNC"
                        "SEND_TO_PLEX" -> "PLX"
                        "ADD_SUBTITLE_TO_MOVIE" -> "SUB"
                        "REGISTER_TRANSFER_HISTORY" -> "HIST"
                        "GLOBAL_STATUS_PROBE" -> "PROB"
                        else -> null
                    }
                } catch (_: Exception) { null }
            }
            if (actionLabel != null) {
                listOf(actionLabel)
            } else {
                val ext = transfer.fileName.substringAfterLast('.', "")
                    .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
                    ?.uppercase() ?: "M4B"
                listOf(ext)
            }
        }
    }

    val isCompleted = transfer.status == CalibreTransferStatus.COMPLETED
    val isVerified = isCompleted && transfer.libraryVerified
    val containerColor = when {
        isVerified -> if (isDark)
            com.damarquez.putz.ui.theme.SuccessGreenContainerDark.copy(alpha = 0.6f)
        else
            com.damarquez.putz.ui.theme.SuccessGreenContainer.copy(alpha = 0.8f)
        isCompleted -> if (isDark)
            com.damarquez.putz.ui.theme.PendingVerifyContainerDark.copy(alpha = 0.6f)
        else
            com.damarquez.putz.ui.theme.PendingVerifyContainer.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val contentColor = when {
        isVerified -> if (isDark) com.damarquez.putz.ui.theme.SuccessGreenDark
            else com.damarquez.putz.ui.theme.SuccessGreen
        isCompleted -> if (isDark) com.damarquez.putz.ui.theme.PendingVerifyAmberDark
            else com.damarquez.putz.ui.theme.PendingVerifyAmber
        else -> MaterialTheme.colorScheme.primary
    }

    Box {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { onTap?.invoke() },
                onLongClick = {
                    showContextMenu = true
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Left column: book icon + format labels
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Book,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp)
                )
                formatLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = contentColor,
                    )
                }
                if (transfer.retryCount > 0) {
                    Text(
                        text = "Tried: ${transfer.retryCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Right column: text info, then status + buttons
            val isAssembled = transfer.status == CalibreTransferStatus.ASSEMBLED
            val isAssemblyUploading = isAssembled && (uploadProgress != null || isPendingAppend)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = transfer.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$dateStr · ${transfer.putioFileId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (transfer.priority) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.PriorityHigh,
                            contentDescription = "Priority",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (transfer.pendingCoverPutioFileId != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Cover staged, will be set once this book is added",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                if (!transfer.errorMessage.isNullOrBlank()) {
                    Text(
                        text = transfer.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Status badge + action buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    StatusBadge(
                        status = transfer.status,
                        uploadProgress = uploadProgress,
                        isAssemblyUploading = isAssemblyUploading,
                        isAppendPending = isPendingAppend,
                        errorMessage = transfer.errorMessage,
                        probeCount = transfer.probeCount,
                        durationSeconds = transfer.durationSeconds,
                        chainPosition = transfer.chainPosition,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isAssembled) {
                        IconButton(
                            onClick = onRetry,
                            enabled = !isAssemblyUploading,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start transfer",
                                tint = if (isAssemblyUploading) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else if (transfer.status == CalibreTransferStatus.PENDING ||
                        transfer.status == CalibreTransferStatus.REQUESTED ||
                        transfer.status == CalibreTransferStatus.PROCESSING) {
                        IconButton(onClick = onProbe) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Probe status",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (transfer.status == CalibreTransferStatus.FAILED) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isCompleted && !transfer.warnings.isNullOrBlank()) {
                    transfer.warnings.split("\n").forEach { warning ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = androidx.compose.ui.graphics.Color(0xFFE65100),
                                modifier = Modifier.size(14.dp).padding(top = 1.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color(0xFFE65100),
                            )
                        }
                    }
                }
            }
        }
    }
    DropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = { showContextMenu = false },
    ) {
        DropdownMenuItem(
            text = { Text("Copy JSON") },
            leadingIcon = {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
            },
            onClick = {
                showContextMenu = false
                onCopyJson(prettyJson.encodeToString(transfer))
            },
        )
        if (transfer.calibreBookUuid != null) {
            DropdownMenuItem(
                text = { Text("Copy UUID") },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onCopyUuid?.invoke(transfer.calibreBookUuid)
                },
            )
        }
        if (transfer.title.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Copy title") },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onCopyTitle?.invoke(transfer.title)
                },
            )
        }
        if (transfer.author.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Copy author") },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onCopyAuthor?.invoke(transfer.author)
                },
            )
        }
        if (!transfer.priority && onMakePriority != null && (
                transfer.status == CalibreTransferStatus.PENDING ||
                transfer.status == CalibreTransferStatus.UPLOADING ||
                transfer.status == CalibreTransferStatus.ASSEMBLED ||
                transfer.status == CalibreTransferStatus.REQUESTED
            )
        ) {
            // CONTRACT: priority requests lane — promotes a not-yet-claimed transfer ahead of
            // everything else pending; see CalibreRepository.promoteTransferToPriority.
            DropdownMenuItem(
                text = { Text("Make priority") },
                leadingIcon = {
                    Icon(Icons.Default.PriorityHigh, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onMakePriority()
                },
            )
        }
        if (onSetCoverFromClipboard != null && (
                transfer.status == CalibreTransferStatus.ASSEMBLED ||
                transfer.status == CalibreTransferStatus.CHAINED
            )
        ) {
            // Stages a clipboard image as this not-yet-dispatched request's future cover —
            // applied automatically via REPLACE_COVER once the book is added and its real
            // calibre_book_uuid is known (see CalibreRepository.attachClipboardCoverToAssembly).
            DropdownMenuItem(
                text = { Text("Set cover from clipboard") },
                leadingIcon = {
                    Icon(Icons.Default.Image, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onSetCoverFromClipboard()
                },
            )
        }
        // CONTRACT: CHAIN
        if (transfer.status == CalibreTransferStatus.CHAINED && onRemoveFromChain != null) {
            DropdownMenuItem(
                text = { Text("Remove from chain (send now)") },
                leadingIcon = {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onRemoveFromChain()
                },
            )
        }
        if (transfer.chainId != null && onOpenChain != null) {
            DropdownMenuItem(
                text = { Text("Part of a chain") },
                leadingIcon = {
                    Icon(Icons.Default.Link, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onOpenChain()
                },
            )
        }
        if (transfer.status == CalibreTransferStatus.COMPLETED) {
            // CONTRACT: probe pattern — re-verifies the book/formats with the daemon and
            // refreshes assets.db for it, same request the in-progress probe button sends;
            // pollResponses tells the two apart by the transfer's current status.
            DropdownMenuItem(
                text = { Text("Check & refresh") },
                leadingIcon = {
                    Icon(Icons.Default.Sync, contentDescription = null)
                },
                onClick = {
                    showContextMenu = false
                    onProbe()
                },
            )
        }
    }
    } // Box
}

@Composable
private fun StatusBadge(status: CalibreTransferStatus, uploadProgress: String? = null, isAssemblyUploading: Boolean = false, isAppendPending: Boolean = false, errorMessage: String? = null, probeCount: Int = 0, durationSeconds: Double? = null, chainPosition: Int? = null) {
    val (icon, color, label) = when (status) {
        CalibreTransferStatus.CHAINED -> Triple(
            Icons.Default.Link,
            MaterialTheme.colorScheme.secondary,
            if (chainPosition != null) "Chained (#${chainPosition + 1})" else "Chained",
        )
        CalibreTransferStatus.UPLOADING -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.tertiary, uploadProgress ?: "Uploading")
        CalibreTransferStatus.ASSEMBLED -> if (isAssemblyUploading) {
            val activeLabel = uploadProgress ?: if (isAppendPending) "Working…" else "Uploading"
            Triple(Icons.Default.Sync, MaterialTheme.colorScheme.tertiary, activeLabel)
        } else {
            Triple(Icons.Default.Sync, MaterialTheme.colorScheme.secondary, "Assembled")
        }
        CalibreTransferStatus.PENDING -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.outline, "Pending")
        CalibreTransferStatus.REQUESTED -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.primary, "Requested")
        CalibreTransferStatus.PROCESSING -> {
            val progressLabel = if (errorMessage?.startsWith("encoding", ignoreCase = true) == true || 
                                   errorMessage?.startsWith("probing", ignoreCase = true) == true) {
                errorMessage
            } else {
                "Processing"
            }
            Triple(Icons.Default.Sync, MaterialTheme.colorScheme.tertiary, progressLabel)
        }
        CalibreTransferStatus.COMPLETED -> {
            val base = when {
                probeCount <= 0 -> "Completed"
                probeCount == 1 -> "Completed and checked"
                else -> "Completed and checked ${probeCount}x"
            }
            val label = if (durationSeconds != null) "$base (${durationSeconds}s)" else base
            Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, label)
        }
        CalibreTransferStatus.FAILED -> Triple(Icons.Default.Error, MaterialTheme.colorScheme.error, "Failed")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
