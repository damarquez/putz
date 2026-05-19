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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import kotlinx.serialization.decodeFromString

private val batchItemsJson = Json { ignoreUnknownKeys = true }
private val prettyJson = Json { prettyPrint = true }

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
    onCopyUuid: ((String) -> Unit)? = null,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var showContextMenu by remember { mutableStateOf(false) }
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(transfer.addedAt))
    val formatLabels: List<String> = remember(transfer.batchData, transfer.fileName) {
        val items = transfer.batchData?.let {
            try { batchItemsJson.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        }
        if (!items.isNullOrEmpty()) {
            items.map { item ->
                when (item.type) {
                    "PACK" -> "M4B"
                    else -> item.fileName.substringAfterLast('.', "")
                        .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
                        ?.uppercase() ?: item.type
                }
            }.distinct()
        } else {
            val ext = transfer.fileName.substringAfterLast('.', "")
                .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
                ?.uppercase() ?: "M4B"
            listOf(ext)
        }
    }

    val isCompleted = transfer.status == CalibreTransferStatus.COMPLETED
    val containerColor = if (isCompleted) {
        if (isDark) {
            com.damarquez.putz.ui.theme.SuccessGreenContainerDark.copy(alpha = 0.6f)
        } else {
            com.damarquez.putz.ui.theme.SuccessGreenContainer.copy(alpha = 0.8f)
        }
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val contentColor = if (isCompleted) {
        if (isDark) {
            com.damarquez.putz.ui.theme.SuccessGreenDark
        } else {
            com.damarquez.putz.ui.theme.SuccessGreen
        }
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .combinedClickable(
                onClick = {},
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
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (!transfer.errorMessage.isNullOrBlank()) {
                    Text(
                        text = transfer.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1
                    )
                }
            }

            val isAssembled = transfer.status == CalibreTransferStatus.ASSEMBLED
            val isAssemblyUploading = isAssembled && uploadProgress != null
            StatusBadge(status = transfer.status, uploadProgress = uploadProgress, isAssemblyUploading = isAssemblyUploading)

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
    }
    } // Box
}

@Composable
private fun StatusBadge(status: CalibreTransferStatus, uploadProgress: String? = null, isAssemblyUploading: Boolean = false) {
    val (icon, color, label) = when (status) {
        CalibreTransferStatus.UPLOADING -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.tertiary, uploadProgress ?: "Uploading")
        CalibreTransferStatus.ASSEMBLED -> if (isAssemblyUploading) {
            Triple(Icons.Default.Sync, MaterialTheme.colorScheme.tertiary, uploadProgress ?: "Uploading")
        } else {
            Triple(Icons.Default.Sync, MaterialTheme.colorScheme.secondary, "Assembled")
        }
        CalibreTransferStatus.PENDING -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.outline, "Pending")
        CalibreTransferStatus.REQUESTED -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.primary, "Requested")
        CalibreTransferStatus.PROCESSING -> Triple(Icons.Default.Sync, MaterialTheme.colorScheme.tertiary, "Processing")
        CalibreTransferStatus.COMPLETED -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "Completed")
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
