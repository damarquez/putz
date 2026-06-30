package com.damarquez.putz.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.repository.AudiobookFile
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.repository.PackGroup
import com.damarquez.putz.ui.components.CompactOutlinedTextField
import com.damarquez.putz.ui.files.CollapsedFileNameText
import com.damarquez.putz.ui.files.ReorderArrowButton
import com.damarquez.putz.util.MetadataUtils
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

/** Per-item edit state: ordered file list + which files are checked (by stable key).
 *  Uses [fileKey] rather than putio_file_id so archive-sourced pack items — where every
 *  AudiobookFile shares the same putio_file_id (the archive's ID) — work correctly.
 *  Keys survive reordering because they are derived from file content, not position.
 *  [isIncluded] controls whether the whole format is kept in the saved transfer. */
private data class ItemEditState(
    val item: CalibreBatchItem,
    val orderedFiles: List<AudiobookFile>,
    val checkedKeys: Set<String>,
    val isIncluded: Boolean = true,
)

/** Stable unique key for one file within a pack, safe for LazyColumn and checked-state tracking.
 *  Regular files: putio_file_id is unique per file.
 *  Archive-entry files: archive_entry path is unique within the pack; putio_file_id is the
 *  same for every entry (the archive's own ID) and cannot be used alone. */
private fun fileKey(file: AudiobookFile): String =
    file.archive_entry ?: file.putio_file_id.toString()

private fun buildEditState(items: List<CalibreBatchItem>): List<ItemEditState> =
    items.map { item ->
        val files = item.files ?: emptyList()
        ItemEditState(
            item = item,
            orderedFiles = files,
            checkedKeys = files.map { fileKey(it) }.toSet(),
            isIncluded = true,
        )
    }

/**
 * Structured view of a Calibre transfer: general info, then a tree of
 * format -> (folder ->) file(s), built entirely from data already present
 * in the transfer's batchData/fileName.
 *
 * When [onSave] is non-null (ASSEMBLED transfers), an edit button is shown.
 * Edit mode allows changing title/author/tags/protected and, within each pack
 * format, reordering or deselecting individual files — matching the pack-sheet UX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferBrowserSheet(
    transfer: CalibreTransferEntity,
    onDismiss: () -> Unit = {},
    onSave: ((title: String, author: String, tags: String?, items: List<CalibreBatchItem>) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()) }
    val originalItems: List<CalibreBatchItem> = remember(transfer.batchData) {
        transfer.batchData?.let {
            try { browserJson.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        } ?: emptyList()
    }

    val originalProtected = originalItems.firstOrNull()?.protected == true

    var isEditMode by rememberSaveable { mutableStateOf(false) }
    var editTitle by rememberSaveable(transfer.title) { mutableStateOf(transfer.title) }
    var editAuthor by rememberSaveable(transfer.author) { mutableStateOf(transfer.author) }
    var editTags by rememberSaveable(transfer.tags) { mutableStateOf(transfer.tags ?: "") }
    var editProtected by rememberSaveable(transfer.batchData) { mutableStateOf(originalProtected) }
    var editStates by remember(transfer.batchData) { mutableStateOf(buildEditState(originalItems)) }
    var expandedInEdit by remember(transfer.batchData) { mutableStateOf(originalItems.indices.toSet()) }
    var collapseNames by rememberSaveable { mutableStateOf(true) }

    fun buildSaveItems(): List<CalibreBatchItem> = editStates.mapNotNull { state ->
        if (!state.isIncluded) return@mapNotNull null
        val protectedValue = if (editProtected) true else null
        val files = state.item.files
        if (files == null) {
            state.item.copy(protected = protectedValue)
        } else {
            val remaining = state.orderedFiles.filter { fileKey(it) in state.checkedKeys }
            if (remaining.isEmpty()) null
            else state.item.copy(files = remaining, protected = protectedValue)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
    ) {
        item {
            if (isEditMode) {
                // ── Edit header ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        editTitle = transfer.title
                        editAuthor = transfer.author
                        editTags = transfer.tags ?: ""
                        editProtected = originalProtected
                        editStates = buildEditState(originalItems)
                        expandedInEdit = originalItems.indices.toSet()
                        isEditMode = false
                    }) { Text("Cancel") }
                    Text("Edit assembly", style = MaterialTheme.typography.titleSmall)
                    val saveItems = buildSaveItems()
                    TextButton(
                        onClick = {
                            onSave?.invoke(
                                editTitle.trim(),
                                editAuthor.trim(),
                                editTags.trim().takeIf { it.isNotBlank() },
                                saveItems,
                            )
                            isEditMode = false
                        },
                        enabled = editTitle.isNotBlank() && saveItems.isNotEmpty(),
                    ) { Text("Save") }
                }
                HorizontalDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    CompactOutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = "Title",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(8.dp))
                    CompactOutlinedTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        label = "Author",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(8.dp))
                    CompactOutlinedTextField(
                        value = editTags,
                        onValueChange = { editTags = it },
                        label = "Tags (comma-separated)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Protected", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Encrypt files on the Calibre side",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = editProtected,
                            onCheckedChange = { editProtected = it },
                        )
                    }
                }
            } else {
                // ── View header ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val fileCount = remember(transfer.allPutioFileIds, transfer.putioFileId) {
                            transfer.parsedFileIds().size
                        }
                        Text(
                            text = "$fileCount file${if (fileCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(transfer.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = transfer.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!transfer.tags.isNullOrBlank()) {
                            Text(
                                text = transfer.tags,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onSave != null) {
                        IconButton(onClick = { isEditMode = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit assembly",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(2.dp))

                InfoRow("Added", dateFormat.format(Date(transfer.addedAt)))
                InfoRow("Status", transfer.status.name.lowercase().replaceFirstChar { it.uppercase() })
                InfoRow("put.io ID", transfer.putioFileId.toString())
                InfoRow(
                    label = "Protected",
                    value = if (originalProtected) "Yes" else "No",
                    icon = if (originalProtected) Icons.Default.Lock else null,
                    valueColor = if (originalProtected) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                transfer.calibreBookUuid?.let { uuid ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "UUID",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = uuid,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(uuid)) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy UUID",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (!transfer.errorMessage.isNullOrBlank()) {
                    InfoRow("Error", transfer.errorMessage, valueColor = MaterialTheme.colorScheme.error)
                }
                if (!transfer.warnings.isNullOrBlank()) {
                    InfoRow("Warnings", transfer.warnings)
                }

                Spacer(Modifier.height(2.dp))
                HorizontalDivider()
            }

            Text(
                text = "Contents",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            )
        }

        // ── Contents ─────────────────────────────────────────────────────────
        if (isEditMode) {
            editStates.forEachIndexed { itemIndex, state ->
                val files = state.item.files
                val expanded = itemIndex in expandedInEdit

                item(key = "edit_header_$itemIndex") {
                    val selectedCount = state.checkedKeys.size
                    val totalCount = files?.size ?: 1
                    val includedCount = editStates.count { it.isIncluded }
                    val isLastIncluded = state.isIncluded && includedCount == 1
                    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Checkbox(
                            checked = state.isIncluded,
                            onCheckedChange = { checked ->
                                editStates = editStates.toMutableList().also {
                                    it[itemIndex] = state.copy(isIncluded = checked)
                                }
                            },
                            enabled = !isLastIncluded,
                        )
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = if (state.isIncluded) MaterialTheme.colorScheme.primary else dimColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatLabelFor(state.item),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.isIncluded) Color.Unspecified else dimColor,
                            )
                            Text(
                                text = if (files != null)
                                    "$selectedCount / $totalCount selected"
                                else
                                    MetadataUtils.stripStubExtension(state.item.fileName),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !state.isIncluded -> dimColor
                                    files != null && selectedCount == 0 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (files != null && state.isIncluded) {
                            IconButton(
                                onClick = {
                                    expandedInEdit = if (expanded)
                                        expandedInEdit - itemIndex
                                    else
                                        expandedInEdit + itemIndex
                                },
                            ) {
                                Icon(
                                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                )
                            }
                        }
                    }
                }

                if (files != null && expanded && state.isIncluded) {
                    // Collapse-names toggle + select-all
                    item(key = "edit_controls_$itemIndex") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 46.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${state.checkedKeys.size} of ${state.orderedFiles.size} selected",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    editStates = editStates.toMutableList().also {
                                        it[itemIndex] = state.copy(
                                            checkedKeys = if (state.checkedKeys.size == state.orderedFiles.size)
                                                emptySet()
                                            else
                                                state.orderedFiles.map { f -> fileKey(f) }.toSet(),
                                        )
                                    }
                                },
                            ) {
                                Text(
                                    if (state.checkedKeys.size == state.orderedFiles.size)
                                        "Deselect all"
                                    else
                                        "Select all",
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 46.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Collapse names",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = collapseNames,
                                onCheckedChange = { collapseNames = it },
                            )
                        }
                    }

                    itemsIndexed(
                        items = state.orderedFiles,
                        key = { _, f -> "edit_file_${itemIndex}_${fileKey(f)}" },
                    ) { fileIndex, file ->
                        val displayName = MetadataUtils.stripStubExtension(file.fileName)
                        val key = fileKey(file)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 36.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = key in state.checkedKeys,
                                onCheckedChange = { checked ->
                                    editStates = editStates.toMutableList().also {
                                        it[itemIndex] = state.copy(
                                            checkedKeys = if (checked)
                                                state.checkedKeys + key
                                            else
                                                state.checkedKeys - key,
                                        )
                                    }
                                },
                            )
                            if (collapseNames) {
                                val prevDisplay = state.orderedFiles.getOrNull(fileIndex - 1)
                                    ?.let { MetadataUtils.stripStubExtension(it.fileName) }
                                val nextDisplay = state.orderedFiles.getOrNull(fileIndex + 1)
                                    ?.let { MetadataUtils.stripStubExtension(it.fileName) }
                                CollapsedFileNameText(
                                    name = displayName,
                                    previousName = prevDisplay,
                                    nextName = nextDisplay,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            ReorderArrowButton(
                                icon = Icons.Default.ArrowUpward,
                                contentDescription = "Move up",
                                enabled = fileIndex > 0,
                                onClick = {
                                    editStates = editStates.toMutableList().also { states ->
                                        val newList = state.orderedFiles.toMutableList()
                                        val tmp = newList[fileIndex]
                                        newList[fileIndex] = newList[fileIndex - 1]
                                        newList[fileIndex - 1] = tmp
                                        states[itemIndex] = state.copy(orderedFiles = newList)
                                    }
                                },
                                onLongClick = {
                                    editStates = editStates.toMutableList().also { states ->
                                        val newList = state.orderedFiles.toMutableList()
                                        newList.add(0, newList.removeAt(fileIndex))
                                        states[itemIndex] = state.copy(orderedFiles = newList)
                                    }
                                },
                            )
                            ReorderArrowButton(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = "Move down",
                                enabled = fileIndex < state.orderedFiles.lastIndex,
                                onClick = {
                                    editStates = editStates.toMutableList().also { states ->
                                        val newList = state.orderedFiles.toMutableList()
                                        val tmp = newList[fileIndex]
                                        newList[fileIndex] = newList[fileIndex + 1]
                                        newList[fileIndex + 1] = tmp
                                        states[itemIndex] = state.copy(orderedFiles = newList)
                                    }
                                },
                                onLongClick = {
                                    editStates = editStates.toMutableList().also { states ->
                                        val newList = state.orderedFiles.toMutableList()
                                        newList.add(newList.removeAt(fileIndex))
                                        states[itemIndex] = state.copy(orderedFiles = newList)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            // ── View mode ─────────────────────────────────────────────────────
            if (originalItems.isEmpty()) {
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
                originalItems.forEachIndexed { index, batchItem ->
                    item(key = "format_$index") {
                        FormatTreeNode(batchItem)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, tint = valueColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            text = MetadataUtils.stripStubExtension(file.fileName),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
