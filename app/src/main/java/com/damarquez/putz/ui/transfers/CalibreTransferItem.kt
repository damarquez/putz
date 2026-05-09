package com.damarquez.putz.ui.transfers

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CalibreTransferItem(
    transfer: CalibreTransferEntity,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(transfer.addedAt))
    val formatLabel = transfer.fileName
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
        ?.uppercase() ?: "M4B"

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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                Text(
                    text = formatLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = contentColor,
                )
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
                if (transfer.status == CalibreTransferStatus.FAILED && !transfer.errorMessage.isNullOrBlank()) {
                    Text(
                        text = transfer.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1
                    )
                }
            }

            StatusBadge(status = transfer.status)

            if (transfer.status == CalibreTransferStatus.PENDING ||
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
}

@Composable
private fun StatusBadge(status: CalibreTransferStatus) {
    val (icon, color, label) = when (status) {
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
