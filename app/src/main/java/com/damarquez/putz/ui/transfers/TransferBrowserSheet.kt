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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.repository.AudiobookFile
import com.damarquez.putz.data.repository.CalibreBatchItem
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.data.repository.PackGroup
import com.damarquez.putz.ui.components.CompactOutlinedTextField
import com.damarquez.putz.ui.files.CollapsedFileNameText
import com.damarquez.putz.ui.files.ImageCompressionLevel
import com.damarquez.putz.ui.files.MergeOutputFormat
import com.damarquez.putz.ui.files.ReorderArrowButton
import com.damarquez.putz.ui.files.openCalibreAnywhereSearch
import com.damarquez.putz.ui.files.compressionApplicableTo
import com.damarquez.putz.ui.files.formatOptionsForItemType
import com.damarquez.putz.util.MetadataUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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
    val editedBatchLabels: Map<Int, String> = emptyMap(),
    // Non-null only for chaptered (groups) items — the "subfolders as chapters" merge-picker
    // shape (see MergePicker.kt "Merge framework"). checkedKeys above is shared across both
    // orderedFiles and every file in every group here, since fileKey() is globally unique.
    val orderedGroups: List<PackGroup>? = null,
    // Non-null only when item.type belongs to a switchable output-format family (e.g. the
    // IMAGE_*_PACK/CBR_*_PACK engines) — see formatOptionsForItemType. Lets the user change
    // e.g. IMAGE_CBZ_PACK to IMAGE_PDF_PACK on an already-staged assembly.
    val formatOptions: List<MergeOutputFormat>? = null,
    val editedFormat: MergeOutputFormat? = null,
    val editedQuality: ImageCompressionLevel = ImageCompressionLevel.ORIGINAL,
)

/** Swaps a pack item's generic output filename (e.g. "Book.cbz") for the new format's,
 * preserving the "_bkp" alt-version suffix addMergeTransfer/applyAltVersion appends. */
private fun applyFormatToFileName(oldName: String, format: MergeOutputFormat): String =
    if (oldName.endsWith("_bkp")) "${format.outputFileName}_bkp" else format.outputFileName

/** Stable unique key for one file within a pack, safe for LazyColumn and checked-state tracking.
 *  Regular files: putio_file_id is unique per file.
 *  Archive-entry files: archive_entry path is unique within the pack; putio_file_id is the
 *  same for every entry (the archive's own ID) and cannot be used alone. */
private fun fileKey(file: AudiobookFile): String =
    file.archive_entry ?: file.putio_file_id.toString()

private fun buildEditState(items: List<CalibreBatchItem>): List<ItemEditState> =
    items.map { item ->
        val files = item.files ?: emptyList()
        val groups = item.groups
        val allKeyedFiles = files + (groups?.flatMap { it.files } ?: emptyList())
        val formatOptions = formatOptionsForItemType(item.type)
        ItemEditState(
            item = item,
            orderedFiles = files,
            orderedGroups = groups,
            checkedKeys = allKeyedFiles.map { fileKey(it) }.toSet(),
            isIncluded = true,
            editedBatchLabels = item.sourceBatchLabels ?: emptyMap(),
            formatOptions = formatOptions,
            editedFormat = formatOptions?.firstOrNull { it.itemType == item.type },
            editedQuality = ImageCompressionLevel.entries.firstOrNull { it.quality == item.image_quality }
                ?: ImageCompressionLevel.ORIGINAL,
        )
    }

/** All "$itemIndex:$groupIndex" keys for [items]' groups, chapters expanded by default —
 *  matches expandedInEdit's "everything starts expanded" default for flat items. */
private fun defaultExpandedGroupKeys(items: List<CalibreBatchItem>): Set<String> =
    items.flatMapIndexed { itemIndex, item ->
        item.groups?.indices?.map { gi -> "$itemIndex:$gi" } ?: emptyList()
    }.toSet()

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
    onSave: ((title: String, author: String, tags: String?, items: List<CalibreBatchItem>, ignoreCover: Boolean, comments: String?) -> Unit)? = null,
    // Manual escape hatch for a FAILED transfer stuck on a title/author match the daemon can't
    // resolve (calibredb's own fuzzy duplicate detection is more permissive than the app's/
    // daemon's title+author lookup — see CalibreRepository.updateFailedTransferAndRetry). Only
    // ever passed for FAILED transfers; ASSEMBLED keeps using [onSave] unchanged.
    onSaveFailedTransfer: ((title: String, author: String, calibreBookUuid: String?, calibreBookId: Long?) -> Unit)? = null,
    checkExistsByUuid: (suspend (String) -> CalibreBookMatch?)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()) }
    val originalItems: List<CalibreBatchItem> = remember(transfer.batchData) {
        transfer.batchData?.let {
            try { browserJson.decodeFromString<List<CalibreBatchItem>>(it) } catch (_: Exception) { null }
        } ?: emptyList()
    }

    val originalProtected = originalItems.firstOrNull()?.protected == true
    val isFailedEdit = onSaveFailedTransfer != null

    var isEditMode by rememberSaveable { mutableStateOf(false) }
    var editTitle by rememberSaveable(transfer.title) { mutableStateOf(transfer.title) }
    var editAuthor by rememberSaveable(transfer.author) { mutableStateOf(transfer.author) }
    var editTags by rememberSaveable(transfer.tags) { mutableStateOf(transfer.tags ?: "") }
    var editComments by rememberSaveable(transfer.comments) { mutableStateOf(transfer.comments ?: "") }
    var editProtected by rememberSaveable(transfer.batchData) { mutableStateOf(originalProtected) }
    var editIgnoreCover by rememberSaveable(transfer.ignoreCover) { mutableStateOf(transfer.ignoreCover) }
    var editStates by remember(transfer.batchData) { mutableStateOf(buildEditState(originalItems)) }
    var expandedInEdit by remember(transfer.batchData) { mutableStateOf(originalItems.indices.toSet()) }
    var expandedGroupsInEdit by remember(transfer.batchData) { mutableStateOf(defaultExpandedGroupKeys(originalItems)) }
    var collapseNames by rememberSaveable { mutableStateOf(true) }

    // FAILED-edit-only fields: pin the retry to an exact existing book (uuid preferred, then
    // numeric id — matches the daemon's own precedence, CONTRACTS.md ADD_BOOK_BATCH), or clear
    // both and rely on the edited title/author alone to dodge calibredb's fuzzy duplicate match.
    var editCalibreBookUuid by rememberSaveable(transfer.calibreBookUuid) { mutableStateOf(transfer.calibreBookUuid ?: "") }
    var editCalibreBookId by rememberSaveable(transfer.calibreBookId) { mutableStateOf(transfer.calibreBookId?.toString() ?: "") }
    var uuidMatch by remember { mutableStateOf<CalibreBookMatch?>(null) }
    var uuidNotFound by remember { mutableStateOf(false) }

    LaunchedEffect(editCalibreBookUuid, isEditMode) {
        val uuid = editCalibreBookUuid.trim()
        if (!isEditMode || !isFailedEdit || checkExistsByUuid == null || uuid.isBlank()) {
            uuidMatch = null
            uuidNotFound = false
            return@LaunchedEffect
        }
        delay(400)
        val match = checkExistsByUuid(uuid)
        uuidMatch = match
        uuidNotFound = match == null
    }

    fun buildSaveItems(): List<CalibreBatchItem> = editStates.mapNotNull { state ->
        if (!state.isIncluded) return@mapNotNull null
        val protectedValue = if (editProtected) true else null
        val format = state.editedFormat
        val newType = format?.itemType ?: state.item.type
        val newFileName = format?.let { applyFormatToFileName(state.item.fileName, it) } ?: state.item.fileName
        val newQuality = if (format != null && compressionApplicableTo(format.itemType)) state.editedQuality.quality else null
        val groups = state.orderedGroups
        if (groups != null) {
            val remainingGroups = groups.mapNotNull { g ->
                val remainingFiles = g.files.filter { fileKey(it) in state.checkedKeys }
                if (remainingFiles.isEmpty()) null else g.copy(files = remainingFiles)
            }
            if (remainingGroups.isEmpty()) null
            else state.item.copy(
                type = newType,
                fileName = newFileName,
                groups = remainingGroups,
                protected = protectedValue,
                image_quality = newQuality,
            )
        } else if (state.item.files == null) {
            state.item.copy(type = newType, fileName = newFileName, protected = protectedValue, image_quality = newQuality)
        } else {
            val remaining = state.orderedFiles.filter { fileKey(it) in state.checkedKeys }
            if (remaining.isEmpty()) null
            else state.item.copy(
                type = newType,
                fileName = newFileName,
                files = remaining,
                protected = protectedValue,
                sourceBatchLabels = state.editedBatchLabels.ifEmpty { null },
                image_quality = newQuality,
            )
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
                        editComments = transfer.comments ?: ""
                        editProtected = originalProtected
                        editIgnoreCover = transfer.ignoreCover
                        editStates = buildEditState(originalItems)
                        expandedInEdit = originalItems.indices.toSet()
                        expandedGroupsInEdit = defaultExpandedGroupKeys(originalItems)
                        editCalibreBookUuid = transfer.calibreBookUuid ?: ""
                        editCalibreBookId = transfer.calibreBookId?.toString() ?: ""
                        isEditMode = false
                    }) { Text("Cancel") }
                    Text(
                        if (isFailedEdit) "Edit & retry" else "Edit assembly",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val saveItems = buildSaveItems()
                    TextButton(
                        onClick = {
                            if (isFailedEdit) {
                                onSaveFailedTransfer?.invoke(
                                    editTitle.trim(),
                                    editAuthor.trim(),
                                    editCalibreBookUuid.trim().takeIf { it.isNotBlank() },
                                    editCalibreBookId.trim().toLongOrNull(),
                                )
                            } else {
                                onSave?.invoke(
                                    editTitle.trim(),
                                    editAuthor.trim(),
                                    editTags.trim().takeIf { it.isNotBlank() },
                                    saveItems,
                                    editIgnoreCover,
                                    editComments.trim().takeIf { it.isNotBlank() },
                                )
                            }
                            isEditMode = false
                        },
                        enabled = editTitle.isNotBlank() && (isFailedEdit || saveItems.isNotEmpty()),
                    ) { Text(if (isFailedEdit) "Save & retry" else "Save") }
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
                    if (isFailedEdit) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Pin this retry to an exact existing book, or leave both blank and " +
                                "rely on the title/author above alone. UUID is tried first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        CompactOutlinedTextField(
                            value = editCalibreBookUuid,
                            onValueChange = { editCalibreBookUuid = it },
                            label = "Calibre book UUID",
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                        when {
                            editCalibreBookUuid.isBlank() -> {}
                            uuidMatch != null -> Text(
                                "Will match: \"${uuidMatch!!.title}\" by ${uuidMatch!!.author}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                            )
                            uuidNotFound -> Text(
                                "UUID not found in the library",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        CompactOutlinedTextField(
                            value = editCalibreBookId,
                            onValueChange = { editCalibreBookId = it.filter { c -> c.isDigit() } },
                            label = "Calibre book ID (numeric, used if UUID is blank)",
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Number),
                        )
                        TextButton(onClick = {
                            openCalibreAnywhereSearch(context, editTitle)
                        }) { Text("Find in CalibreAnywhere") }
                    }
                }
                if (!isFailedEdit) Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    CompactOutlinedTextField(
                        value = editTags,
                        onValueChange = { editTags = it },
                        label = "Tags (comma-separated)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Spacer(Modifier.height(8.dp))
                    CompactOutlinedTextField(
                        value = editComments,
                        onValueChange = { editComments = it },
                        label = "Comments (HTML supported)",
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
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
                            onCheckedChange = { editProtected = it; if (!it) editIgnoreCover = false },
                        )
                    }
                    if (editProtected) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Ignore random cover", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Don't generate an obfuscated cover — behave like an unprotected book",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = editIgnoreCover,
                                onCheckedChange = { editIgnoreCover = it },
                            )
                        }
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
                    if (onSave != null || onSaveFailedTransfer != null) {
                        IconButton(onClick = { isEditMode = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = if (isFailedEdit) "Edit & retry" else "Edit assembly",
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
        // FAILED-edit mode has no item picker (files aren't being re-selected, only the match
        // target) — falls through to the read-only tree in the `else` branch below instead.
        if (isEditMode && !isFailedEdit) {
            editStates.forEachIndexed { itemIndex, state ->
                val files = state.item.files
                val groups = state.orderedGroups
                val hasChildren = files != null || groups != null
                val expanded = itemIndex in expandedInEdit

                item(key = "edit_header_$itemIndex") {
                    val selectedCount = state.checkedKeys.size
                    val totalCount = files?.size ?: groups?.sumOf { it.files.size } ?: 1
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
                                text = if (hasChildren)
                                    "$selectedCount / $totalCount selected"
                                else
                                    MetadataUtils.stripStubExtension(state.item.fileName),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !state.isIncluded -> dimColor
                                    hasChildren && selectedCount == 0 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (hasChildren && state.isIncluded) {
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

                if (state.isIncluded && state.formatOptions != null) {
                    item(key = "edit_format_$itemIndex") {
                        Column(modifier = Modifier.padding(start = 46.dp, end = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Output format",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                state.formatOptions.forEach { fmt ->
                                    FilterChip(
                                        selected = state.editedFormat == fmt,
                                        onClick = {
                                            editStates = editStates.toMutableList().also {
                                                it[itemIndex] = state.copy(editedFormat = fmt)
                                            }
                                        },
                                        label = { Text(fmt.label) },
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                            // Compression only applies to the PDF-from-images engines
                            // (ImagePdfPackJob/CbrPdfPackJob) — CBZ/CBR/EPUB embed source
                            // image bytes as-is by design (e.g. to keep animated GIFs intact).
                            if (state.editedFormat?.let { compressionApplicableTo(it.itemType) } == true) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Compression",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ImageCompressionLevel.entries.forEach { level ->
                                        FilterChip(
                                            selected = state.editedQuality == level,
                                            onClick = {
                                                editStates = editStates.toMutableList().also {
                                                    it[itemIndex] = state.copy(editedQuality = level)
                                                }
                                            },
                                            label = { Text(level.label) },
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                }
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

                    val hasBatches = state.orderedFiles.map { it.sourceBatch ?: 1 }.distinct().size > 1
                    var prevBatchNum: Int? = null
                    state.orderedFiles.forEachIndexed { fileIndex, file ->
                        val fileBatch = file.sourceBatch ?: 1
                        if (hasBatches && fileBatch != prevBatchNum) {
                            prevBatchNum = fileBatch
                            val capturedBatch = fileBatch
                            item(key = "edit_batch_${itemIndex}_$capturedBatch") {
                                val labelText = state.editedBatchLabels[capturedBatch] ?: "Part $capturedBatch"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 46.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    CompactOutlinedTextField(
                                        value = labelText,
                                        onValueChange = { newLabel ->
                                            editStates = editStates.toMutableList().also { states ->
                                                val newLabels = state.editedBatchLabels.toMutableMap()
                                                newLabels[capturedBatch] = newLabel
                                                states[itemIndex] = state.copy(editedBatchLabels = newLabels)
                                            }
                                        },
                                        label = "Part label",
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    )
                                }
                            }
                        }
                        val capturedIndex = fileIndex
                        val capturedFile = file
                        item(key = "edit_file_${itemIndex}_${fileKey(file)}") {
                            val displayName = MetadataUtils.stripStubExtension(capturedFile.fileName)
                            val key = fileKey(capturedFile)
                            val batchOfFile = capturedFile.sourceBatch ?: 1
                            val startPad = if (hasBatches) 56.dp else 36.dp
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = startPad, end = 8.dp),
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
                                    val prevDisplay = state.orderedFiles.getOrNull(capturedIndex - 1)
                                        ?.let { MetadataUtils.stripStubExtension(it.fileName) }
                                    val nextDisplay = state.orderedFiles.getOrNull(capturedIndex + 1)
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
                                    )
                                }
                                ReorderArrowButton(
                                    icon = Icons.Default.ArrowUpward,
                                    contentDescription = "Move up",
                                    enabled = capturedIndex > 0 &&
                                        (!hasBatches || (state.orderedFiles[capturedIndex - 1].sourceBatch ?: 1) == batchOfFile),
                                    onClick = {
                                        editStates = editStates.toMutableList().also { states ->
                                            val newList = state.orderedFiles.toMutableList()
                                            val tmp = newList[capturedIndex]
                                            newList[capturedIndex] = newList[capturedIndex - 1]
                                            newList[capturedIndex - 1] = tmp
                                            states[itemIndex] = state.copy(orderedFiles = newList)
                                        }
                                    },
                                    onLongClick = {
                                        editStates = editStates.toMutableList().also { states ->
                                            val newList = state.orderedFiles.toMutableList()
                                            if (hasBatches) {
                                                val batchStart = newList.indexOfFirst { (it.sourceBatch ?: 1) == batchOfFile }
                                                newList.add(batchStart, newList.removeAt(capturedIndex))
                                            } else {
                                                newList.add(0, newList.removeAt(capturedIndex))
                                            }
                                            states[itemIndex] = state.copy(orderedFiles = newList)
                                        }
                                    },
                                )
                                ReorderArrowButton(
                                    icon = Icons.Default.ArrowDownward,
                                    contentDescription = "Move down",
                                    enabled = capturedIndex < state.orderedFiles.lastIndex &&
                                        (!hasBatches || (state.orderedFiles[capturedIndex + 1].sourceBatch ?: 1) == batchOfFile),
                                    onClick = {
                                        editStates = editStates.toMutableList().also { states ->
                                            val newList = state.orderedFiles.toMutableList()
                                            val tmp = newList[capturedIndex]
                                            newList[capturedIndex] = newList[capturedIndex + 1]
                                            newList[capturedIndex + 1] = tmp
                                            states[itemIndex] = state.copy(orderedFiles = newList)
                                        }
                                    },
                                    onLongClick = {
                                        editStates = editStates.toMutableList().also { states ->
                                            val newList = state.orderedFiles.toMutableList()
                                            if (hasBatches) {
                                                val batchEnd = newList.indexOfLast { (it.sourceBatch ?: 1) == batchOfFile }
                                                newList.add(batchEnd, newList.removeAt(capturedIndex))
                                            } else {
                                                newList.add(newList.removeAt(capturedIndex))
                                            }
                                            states[itemIndex] = state.copy(orderedFiles = newList)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (groups != null && expanded && state.isIncluded) {
                    // Collapse-names toggle + select-all across every chapter's files
                    item(key = "edit_group_controls_$itemIndex") {
                        val allGroupFiles = groups.flatMap { it.files }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 46.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${state.checkedKeys.size} of ${allGroupFiles.size} selected",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    editStates = editStates.toMutableList().also {
                                        it[itemIndex] = state.copy(
                                            checkedKeys = if (state.checkedKeys.size == allGroupFiles.size)
                                                emptySet()
                                            else
                                                allGroupFiles.map { f -> fileKey(f) }.toSet(),
                                        )
                                    }
                                },
                            ) {
                                Text(if (state.checkedKeys.size == allGroupFiles.size) "Deselect all" else "Select all")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 46.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Collapse names", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Switch(checked = collapseNames, onCheckedChange = { collapseNames = it })
                        }
                    }

                    groups.forEachIndexed { groupIndex, group ->
                        val groupKey = "$itemIndex:$groupIndex"
                        val groupExpanded = groupKey in expandedGroupsInEdit
                        val capturedGroupIndex = groupIndex

                        item(key = "edit_chapter_${itemIndex}_$groupIndex") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 46.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(group.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${group.files.count { fileKey(it) in state.checkedKeys }} of ${group.files.size} selected",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    expandedGroupsInEdit = if (groupExpanded) expandedGroupsInEdit - groupKey else expandedGroupsInEdit + groupKey
                                }) {
                                    Icon(
                                        if (groupExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (groupExpanded) "Collapse chapter" else "Expand chapter",
                                    )
                                }
                                ReorderArrowButton(
                                    icon = Icons.Default.ArrowUpward,
                                    contentDescription = "Move up",
                                    enabled = groupIndex > 0,
                                    onClick = {
                                        if (groupIndex > 0) editStates = editStates.toMutableList().also { states ->
                                            val newGroups = groups.toMutableList()
                                            val tmp = newGroups[groupIndex]; newGroups[groupIndex] = newGroups[groupIndex - 1]; newGroups[groupIndex - 1] = tmp
                                            states[itemIndex] = state.copy(orderedGroups = newGroups)
                                        }
                                    },
                                    onLongClick = {
                                        if (groupIndex > 0) editStates = editStates.toMutableList().also { states ->
                                            val newGroups = groups.toMutableList()
                                            newGroups.add(0, newGroups.removeAt(groupIndex))
                                            states[itemIndex] = state.copy(orderedGroups = newGroups)
                                        }
                                    },
                                )
                                ReorderArrowButton(
                                    icon = Icons.Default.ArrowDownward,
                                    contentDescription = "Move down",
                                    enabled = groupIndex < groups.lastIndex,
                                    onClick = {
                                        if (groupIndex < groups.lastIndex) editStates = editStates.toMutableList().also { states ->
                                            val newGroups = groups.toMutableList()
                                            val tmp = newGroups[groupIndex]; newGroups[groupIndex] = newGroups[groupIndex + 1]; newGroups[groupIndex + 1] = tmp
                                            states[itemIndex] = state.copy(orderedGroups = newGroups)
                                        }
                                    },
                                    onLongClick = {
                                        if (groupIndex < groups.lastIndex) editStates = editStates.toMutableList().also { states ->
                                            val newGroups = groups.toMutableList()
                                            newGroups.add(newGroups.removeAt(groupIndex))
                                            states[itemIndex] = state.copy(orderedGroups = newGroups)
                                        }
                                    },
                                )
                            }
                        }

                        if (groupExpanded) {
                            group.files.forEachIndexed { fileIndex, file ->
                                val capturedFileIndex = fileIndex
                                val capturedFile = file
                                item(key = "edit_gfile_${itemIndex}_${groupIndex}_${fileKey(file)}") {
                                    val displayName = MetadataUtils.stripStubExtension(capturedFile.fileName)
                                    val key = fileKey(capturedFile)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 66.dp, end = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = key in state.checkedKeys,
                                            onCheckedChange = { checked ->
                                                editStates = editStates.toMutableList().also {
                                                    it[itemIndex] = state.copy(
                                                        checkedKeys = if (checked) state.checkedKeys + key else state.checkedKeys - key,
                                                    )
                                                }
                                            },
                                        )
                                        if (collapseNames) {
                                            val prevDisplay = group.files.getOrNull(capturedFileIndex - 1)
                                                ?.let { MetadataUtils.stripStubExtension(it.fileName) }
                                            val nextDisplay = group.files.getOrNull(capturedFileIndex + 1)
                                                ?.let { MetadataUtils.stripStubExtension(it.fileName) }
                                            CollapsedFileNameText(
                                                name = displayName,
                                                previousName = prevDisplay,
                                                nextName = nextDisplay,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f),
                                            )
                                        } else {
                                            Text(text = displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        }
                                        ReorderArrowButton(
                                            icon = Icons.Default.ArrowUpward,
                                            contentDescription = "Move up",
                                            enabled = capturedFileIndex > 0,
                                            onClick = {
                                                if (capturedFileIndex > 0) editStates = editStates.toMutableList().also { states ->
                                                    val newGroups = groups.toMutableList()
                                                    val newFiles = newGroups[capturedGroupIndex].files.toMutableList()
                                                    val tmp = newFiles[capturedFileIndex]; newFiles[capturedFileIndex] = newFiles[capturedFileIndex - 1]; newFiles[capturedFileIndex - 1] = tmp
                                                    newGroups[capturedGroupIndex] = newGroups[capturedGroupIndex].copy(files = newFiles)
                                                    states[itemIndex] = state.copy(orderedGroups = newGroups)
                                                }
                                            },
                                            onLongClick = {
                                                if (capturedFileIndex > 0) editStates = editStates.toMutableList().also { states ->
                                                    val newGroups = groups.toMutableList()
                                                    val newFiles = newGroups[capturedGroupIndex].files.toMutableList()
                                                    newFiles.add(0, newFiles.removeAt(capturedFileIndex))
                                                    newGroups[capturedGroupIndex] = newGroups[capturedGroupIndex].copy(files = newFiles)
                                                    states[itemIndex] = state.copy(orderedGroups = newGroups)
                                                }
                                            },
                                        )
                                        ReorderArrowButton(
                                            icon = Icons.Default.ArrowDownward,
                                            contentDescription = "Move down",
                                            enabled = capturedFileIndex < group.files.lastIndex,
                                            onClick = {
                                                if (capturedFileIndex < group.files.lastIndex) editStates = editStates.toMutableList().also { states ->
                                                    val newGroups = groups.toMutableList()
                                                    val newFiles = newGroups[capturedGroupIndex].files.toMutableList()
                                                    val tmp = newFiles[capturedFileIndex]; newFiles[capturedFileIndex] = newFiles[capturedFileIndex + 1]; newFiles[capturedFileIndex + 1] = tmp
                                                    newGroups[capturedGroupIndex] = newGroups[capturedGroupIndex].copy(files = newFiles)
                                                    states[itemIndex] = state.copy(orderedGroups = newGroups)
                                                }
                                            },
                                            onLongClick = {
                                                if (capturedFileIndex < group.files.lastIndex) editStates = editStates.toMutableList().also { states ->
                                                    val newGroups = groups.toMutableList()
                                                    val newFiles = newGroups[capturedGroupIndex].files.toMutableList()
                                                    newFiles.add(newFiles.removeAt(capturedFileIndex))
                                                    newGroups[capturedGroupIndex] = newGroups[capturedGroupIndex].copy(files = newFiles)
                                                    states[itemIndex] = state.copy(orderedGroups = newGroups)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
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
                val batches = files.groupBy { it.sourceBatch ?: 1 }
                if (batches.size > 1) {
                    val labels = item.sourceBatchLabels ?: emptyMap()
                    batches.entries.sortedBy { it.key }.forEach { (batchIdx, batchFiles) ->
                        val label = labels[batchIdx] ?: "Part $batchIdx"
                        GroupTreeNode(PackGroup(label = label, files = batchFiles), indent = 1)
                    }
                } else {
                    files.forEach { file -> FileLeafRow(file, indent = 1) }
                }
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
        )
    }
}
