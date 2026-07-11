package com.damarquez.putz.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.ui.components.CompactOutlinedTextField
import com.damarquez.putz.util.MetadataUtils

/** One row of the batch send-to-Calibre draft: a selected file plus its editable title/author. */
data class CalibreBatchDraftItem(
    val file: PutioFile,
    val title: String,
    val author: String,
    val included: Boolean = true,
    val isProtected: Boolean = false,
    val isAltVersion: Boolean = false,
    val tags: String = "",
    val uuid: String = "",
    val matchedBookId: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreBatchConfirmationSheet(
    items: List<CalibreBatchDraftItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<CalibreBatchDraftItem>) -> Unit,
    onItemChange: (CalibreBatchDraftItem) -> Unit,
    checkExists: suspend (String, String) -> Long?,
    checkExistsByUuid: suspend (String) -> CalibreBookMatch?,
    checkPendingTransfer: suspend (Long, String) -> CalibreTransferEntity? = { _, _ -> null },
    onPreview: (PutioFile) -> Unit,
) {
    val includedItems = items.filter { it.included }
    val canSend = includedItems.isNotEmpty() && includedItems.all { it.title.isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Send to Calibre",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "${includedItems.size} of ${items.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(items, key = { _, item -> item.file.id }) { index, item ->
                        CalibreBatchRow(
                            item = item,
                            index = if (items.size > 1) index + 1 else null,
                            checkExists = checkExists,
                            checkExistsByUuid = checkExistsByUuid,
                            checkPendingTransfer = checkPendingTransfer,
                            onChange = onItemChange,
                            onPreview = onPreview,
                            // CONTRACT: bulk-select popup — long-pressing this row's checkbox
                            // shows "select all / unselect all / unselect all from here" instead
                            // of just toggling this one item, mirroring the same long-press
                            // popup on the main files list (FileItem.kt). Implemented here (not
                            // passed in from the caller) since this composable already owns the
                            // full `items` list and the per-item onItemChange callback needed to
                            // apply a bulk change — each just replays onItemChange per row.
                            onSelectAll = { items.forEach { onItemChange(it.copy(included = true)) } },
                            onUnselectAll = { items.forEach { onItemChange(it.copy(included = false)) } },
                            onUnselectFromHere = {
                                items.subList(index, items.size).forEach { onItemChange(it.copy(included = false)) }
                            },
                        )
                        HorizontalDivider()
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { onConfirm(includedItems) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSend,
                ) {
                    Text("Send (${includedItems.size})")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalibreBatchRow(
    item: CalibreBatchDraftItem,
    index: Int?,
    checkExists: suspend (String, String) -> Long?,
    checkExistsByUuid: suspend (String) -> CalibreBookMatch?,
    checkPendingTransfer: suspend (Long, String) -> CalibreTransferEntity?,
    onChange: (CalibreBatchDraftItem) -> Unit,
    onPreview: (PutioFile) -> Unit,
    onSelectAll: () -> Unit,
    onUnselectAll: () -> Unit,
    onUnselectFromHere: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var matchedBookId by remember(item.file.id) { mutableStateOf<Long?>(null) }
    var matchedBookTitle by remember(item.file.id) { mutableStateOf<String?>(null) }
    var matchedBookAuthor by remember(item.file.id) { mutableStateOf<String?>(null) }
    var isUuidMatched by remember(item.file.id) { mutableStateOf(false) }
    var pendingTransfer by remember(item.file.id) { mutableStateOf<CalibreTransferEntity?>(null) }
    var showBulkMenu by remember { mutableStateOf(false) }

    // A UUID match takes over title/author (like the single-file dialog) and owns matchedBookId
    // while present; the text-based match below only runs when there's no UUID to match on.
    LaunchedEffect(item.uuid) {
        if (item.uuid.isNotBlank()) {
            val match = checkExistsByUuid(item.uuid.trim())
            if (match != null) {
                matchedBookId = match.id
                matchedBookTitle = match.title
                matchedBookAuthor = match.author
                isUuidMatched = true
                if (item.title != match.title || item.author != match.author) {
                    onChange(item.copy(title = match.title, author = match.author))
                }
            } else {
                matchedBookId = null
                matchedBookTitle = null
                matchedBookAuthor = null
                isUuidMatched = false
            }
        } else {
            isUuidMatched = false
            matchedBookTitle = null
            matchedBookAuthor = null
        }
    }

    LaunchedEffect(item.title, item.author, item.included, item.uuid) {
        if (item.uuid.isNotBlank()) return@LaunchedEffect
        matchedBookId = if (item.included && item.title.isNotBlank()) {
            checkExists(item.title, item.author.ifBlank { "Unknown" })
        } else {
            null
        }
    }

    LaunchedEffect(item.file.id) {
        pendingTransfer = checkPendingTransfer(item.file.syncedFileId, item.file.displayName)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (index != null) {
                Text(
                    text = "$index.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
            }
            Box {
                Row(
                    modifier = Modifier.combinedClickable(
                        onClick = { onChange(item.copy(included = !item.included)) },
                        onLongClick = { showBulkMenu = true },
                    ),
                ) {
                    Checkbox(checked = item.included, onCheckedChange = null)
                }
                DropdownMenu(
                    expanded = showBulkMenu,
                    onDismissRequest = { showBulkMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        onClick = { showBulkMenu = false; onSelectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text("Unselect all") },
                        onClick = { showBulkMenu = false; onUnselectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text("Unselect all from here") },
                        onClick = { showBulkMenu = false; onUnselectFromHere() },
                    )
                }
            }
            Text(
                text = item.file.displayName,
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onPreview(item.file) },
            )
        }

        if (item.included) {
            // A UUID match only targets an existing book for a new format — the daemon never
            // writes title/author back for it (see process_book_batch in putz_manager.py), so
            // editing these fields once matched would be misleading. Lock them, mirroring the
            // single-file dialog.
            Spacer(Modifier.height(4.dp))
            CompactOutlinedTextField(
                value = item.title,
                onValueChange = { onChange(item.copy(title = it)) },
                label = "Title",
                modifier = Modifier.fillMaxWidth(),
                enabled = !isUuidMatched,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = if (!isUuidMatched) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onChange(item.copy(title = item.file.displayName.substringBeforeLast('.'))) },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileOpen,
                                    contentDescription = "Load filename as title",
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                )
                            }
                            IconButton(
                                onClick = { onChange(item.copy(title = MetadataUtils.fixTitleCapitalization(item.title))) },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = "Fix title capitalisation",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                } else null,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactOutlinedTextField(
                    value = item.author,
                    onValueChange = { onChange(item.copy(author = it)) },
                    label = "Author",
                    placeholder = "Unknown",
                    modifier = Modifier.weight(1f),
                    enabled = !isUuidMatched,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { onChange(item.copy(title = item.author, author = item.title)) },
                    enabled = !isUuidMatched,
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Swap title and author",
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactOutlinedTextField(
                    value = item.uuid,
                    onValueChange = { onChange(item.copy(uuid = it)) },
                    label = "Book UUID (Optional)",
                    modifier = Modifier.weight(1f),
                    placeholder = "Directly target an existing book",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        val pasted = clipboardManager.getText()?.text.orEmpty().trim()
                        if (pasted.isNotEmpty()) onChange(item.copy(uuid = pasted))
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste UUID from clipboard",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            CompactOutlinedTextField(
                value = item.tags,
                onValueChange = { onChange(item.copy(tags = it)) },
                label = "Tags (optional)",
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Programming, Python, Reference",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Encrypt book",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = item.isProtected,
                    onCheckedChange = { onChange(item.copy(isProtected = it)) },
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alternative version",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Append _bkp to extension (e.g. pdf → pdf_bkp)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = item.isAltVersion,
                    onCheckedChange = { onChange(item.copy(isAltVersion = it)) },
                )
            }

            if (pendingTransfer != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(16.dp).width(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Already has a pending transfer (${pendingTransfer!!.status})!",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (matchedBookId != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openCalibreAnywhereSearch(context, matchedBookTitle ?: item.title) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isUuidMatched) Icons.Default.FileOpen else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isUuidMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(16.dp).width(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isUuidMatched) {
                            "Matched: $matchedBookTitle by $matchedBookAuthor (ID: #$matchedBookId)"
                        } else {
                            "Might already exist in Calibre! (ID: #$matchedBookId)"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = if (isUuidMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
