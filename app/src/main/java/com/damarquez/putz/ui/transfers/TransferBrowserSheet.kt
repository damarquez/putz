package com.damarquez.putz.ui.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.repository.AudiobookFile
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.repository.PackGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val browserJson = Json { ignoreUnknownKeys = true }

private fun singleFileLabel(fileName: String): String =
    fileName.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
        ?.uppercase() ?: "FILE"

/**
 * Structured view of a Calibre transfer: general info, then a tree of
 * format -> (folder ->) file(s), built entirely from data already present
 * in the transfer's batchData/fileName — no extra daemon round-trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferBrowserSheet(transfer: CalibreTransferEntity) {
    val clipboard = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()) }
    val items: List<CalibreBatchItem> = remember(transfer.batchData) {
        transfer.batchData?.let {
            try { browserJson.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        } ?: emptyList()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(transfer.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = transfer.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Added") },
                trailingContent = {
                    Text(
                        text = dateFormat.format(Date(transfer.addedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Status") },
                trailingContent = {
                    Text(
                        text = transfer.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            ListItem(
                headlineContent = { Text("put.io ID") },
                trailingContent = {
                    Text(
                        text = transfer.putioFileId.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            transfer.calibreBookUuid?.let { uuid ->
                ListItem(
                    headlineContent = { Text("UUID") },
                    supportingContent = {
                        Text(
                            text = uuid,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(uuid)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy UUID")
                        }
                    },
                )
            }
            if (!transfer.errorMessage.isNullOrBlank()) {
                ListItem(
                    headlineContent = { Text("Error") },
                    supportingContent = {
                        Text(
                            text = transfer.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            if (!transfer.warnings.isNullOrBlank()) {
                ListItem(
                    headlineContent = { Text("Warnings") },
                    supportingContent = {
                        Text(
                            text = transfer.warnings,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            HorizontalDivider()
            ListItem(
                headlineContent = {
                    Text("Contents", style = MaterialTheme.typography.titleSmall)
                },
            )
        }

        if (items.isEmpty()) {
            item {
                TreeRow(
                    icon = Icons.Default.Description,
                    label = singleFileLabel(transfer.fileName),
                    subtitle = transfer.fileName,
                    indent = 0,
                    expandable = false,
                    expanded = false,
                    onToggle = {},
                )
            }
        } else {
            items.forEachIndexed { index, batchItem ->
                item(key = "format_$index") {
                    FormatTreeNode(batchItem)
                }
            }
        }
    }
}

@Composable
private fun FormatTreeNode(item: CalibreBatchItem) {
    var expanded by remember { mutableStateOf(true) }
    val groups = item.groups
    val files = item.files
    val hasChildren = !groups.isNullOrEmpty() || !files.isNullOrEmpty()

    Column {
        TreeRow(
            icon = Icons.Default.Description,
            label = formatLabelFor(item),
            subtitle = item.fileName,
            indent = 0,
            expandable = hasChildren,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            if (!groups.isNullOrEmpty()) {
                groups.forEach { group -> GroupTreeNode(group, indent = 1) }
            } else if (!files.isNullOrEmpty()) {
                files.forEach { file -> FileLeafRow(file, indent = 1) }
            }
        }
    }
}

@Composable
private fun GroupTreeNode(group: PackGroup, indent: Int) {
    var expanded by remember { mutableStateOf(true) }

    Column {
        TreeRow(
            icon = Icons.Default.Folder,
            label = group.label,
            subtitle = "${group.files.size} file${if (group.files.size == 1) "" else "s"}",
            indent = indent,
            expandable = group.files.isNotEmpty(),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            group.files.forEach { file -> FileLeafRow(file, indent = indent + 1) }
        }
    }
}

@Composable
private fun TreeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String?,
    indent: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + indent * 20).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expandable) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }
    }
}

@Composable
private fun FileLeafRow(file: AudiobookFile, indent: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + indent * 20).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = file.fileName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
