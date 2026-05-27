package com.damarquez.putz.ui.transfers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.MergedTransfer
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.model.TransferStatus
import com.damarquez.putz.ui.theme.LocalAppStyling

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferItem(
    merged: MergedTransfer,
    onCopyMagnet: (String) -> Unit,
    onStop: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onEditName: (Long, String) -> Unit,
    onGoToFiles: (Long) -> Unit,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val transfer = merged.transfer
    val styling = LocalAppStyling.current
    val status = TransferStatus.from(transfer.status)
    val cornerRadius = styling.cornerRadiusDp.dp

    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val displayName = merged.historyEntry?.resolvedName?.takeIf { it.isNotBlank() }
        ?: merged.appDisplayName

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTap?.invoke() },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransferStatusIcon(status = status, cornerRadius = cornerRadius.value.toInt())

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit display name") },
                            onClick = {
                                showMenu = false
                                onEditName(transfer.id, merged.appDisplayName)
                            }
                        )
                        if (status == TransferStatus.COMPLETED && transfer.fileId != null) {
                            DropdownMenuItem(
                                text = { Text("Go to files") },
                                onClick = {
                                    showMenu = false
                                    onGoToFiles(transfer.fileId)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove from list", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onRemove(transfer.id)
                            }
                        )
                    }

                    if (merged.magnetLink != null) {
                        androidx.compose.material3.IconButton(
                            onClick = { 
                                println("TransferItem: Copy clicked for ${merged.appDisplayName}")
                                onCopyMagnet(merged.magnetLink) 
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Copy magnet link",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (merged.isStopped) {
                        androidx.compose.material3.IconButton(
                            onClick = { 
                                println("TransferItem: Resume clicked for id ${transfer.id}")
                                onResume(transfer.id) 
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    } else {
                        androidx.compose.material3.IconButton(
                            onClick = { 
                                println("TransferItem: Stop clicked for id ${transfer.id}")
                                onStop(transfer.id) 
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    androidx.compose.material3.IconButton(
                        onClick = { 
                            println("TransferItem: Remove clicked for id ${transfer.id}")
                            onRemove(transfer.id) 
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                if (merged.isStopped && status != TransferStatus.COMPLETED) {
                    StoppedInfo(merged = merged)
                } else {
                    when (status) {
                    TransferStatus.DOWNLOADING, TransferStatus.COMPLETING -> {
                        DownloadProgress(transfer = transfer)
                    }
                    TransferStatus.SEEDING -> {
                        SeedingInfo(transfer = transfer)
                    }
                    TransferStatus.COMPLETED -> {
                        CompletedInfo(transfer = transfer)
                    }
                    TransferStatus.ERROR, TransferStatus.STOPPED -> {
                        ErrorInfo(transfer = transfer)
                    }
                    TransferStatus.WAITING, TransferStatus.IN_QUEUE,
                    TransferStatus.PREPARING_DOWNLOAD -> {
                        QueuedInfo(transfer = transfer)
                    }
                }
                }
            }
        }

        if (styling.isEink) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun DownloadProgress(transfer: PutioTransfer) {
    val percent = transfer.percentDone / 100f

    LinearProgressIndicator(
        progress = { percent.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        strokeCap = StrokeCap.Round,
    )

    Spacer(Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${transfer.percentDone}%  ${formatSize(transfer.downloaded)} / ${formatSize(transfer.size)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (transfer.downSpeed > 0) {
                Icon(
                    Icons.Default.ArrowDownward, null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatSpeed(transfer.downSpeed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (transfer.upSpeed > 0) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.ArrowUpward, null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatSpeed(transfer.upSpeed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    transfer.estimatedTime?.let { eta ->
        if (eta > 0 && eta < 86_400 * 7) {
            Text(
                text = "ETA: ${formatEta(eta)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (transfer.peersConnected > 0) {
        Text(
            text = "${transfer.peersConnected} peers",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeedingInfo(transfer: PutioTransfer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.ArrowUpward, null,
            modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = buildString {
                append("Ratio %.2f".format(transfer.currentRatio))
                if (transfer.upSpeed > 0) append("  •  ${formatSpeed(transfer.upSpeed)}")
                if (transfer.peersGettingFromUs > 0) append("  •  ${transfer.peersGettingFromUs} leechers")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompletedInfo(transfer: PutioTransfer) {
    Text(
        text = buildString {
            if (transfer.size > 0) append(formatSize(transfer.size))
            transfer.finishedAt?.let { append("  •  Finished ${it.take(10)}") }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorInfo(transfer: PutioTransfer) {
    val msg = transfer.errorMessage?.takeIf { it.isNotBlank() }
        ?: transfer.trackerMessage?.takeIf { it.isNotBlank() }
        ?: transfer.status.lowercase().replaceFirstChar { it.uppercase() }
    Text(
        text = msg,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QueuedInfo(transfer: PutioTransfer) {
    Text(
        text = buildString {
            append(TransferStatus.from(transfer.status).label)
            if (transfer.size > 0) append("  •  ${formatSize(transfer.size)}")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StoppedInfo(merged: MergedTransfer) {
    Text(
        text = "Stopped locally",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TransferStatusIcon(status: TransferStatus, cornerRadius: Int) {
    val styling = LocalAppStyling.current
    val shape = RoundedCornerShape(cornerRadius.dp)

    val (icon, tint, bg) = when (status) {
        TransferStatus.DOWNLOADING, TransferStatus.COMPLETING ->
            Triple(Icons.Default.Sync, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        TransferStatus.SEEDING ->
            Triple(Icons.Default.Upload, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
        TransferStatus.PREPARING_DOWNLOAD ->
            Triple(Icons.Default.HourglassTop, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        TransferStatus.COMPLETED ->
            Triple(Icons.Default.CheckCircle, Color(0xFF4CAF50), Color(0xFFE8F5E9))
        TransferStatus.ERROR ->
            Triple(Icons.Default.Error, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        TransferStatus.STOPPED ->
            Triple(Icons.Default.Pause, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        TransferStatus.WAITING, TransferStatus.IN_QUEUE ->
            Triple(Icons.Default.Pending, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }

    val isSpinning = status == TransferStatus.DOWNLOADING || status == TransferStatus.COMPLETING
    val rotation = if (isSpinning && styling.useAnimations) {
        val transition = rememberInfiniteTransition(label = "spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "spin",
        )
        angle
    } else 0f

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(bg)
            .then(if (styling.useBorders) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = status.label,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    bytes > 0 -> "$bytes B"
    else -> ""
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000L -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
    bytesPerSec >= 1_000L -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
    else -> "$bytesPerSec B/s"
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
