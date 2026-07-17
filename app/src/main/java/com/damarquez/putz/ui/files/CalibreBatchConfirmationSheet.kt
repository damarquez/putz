package com.damarquez.putz.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PersonSearch
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.ui.components.CompactOutlinedTextField
import com.damarquez.putz.ui.components.transientVerticalScrollbar
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
    // Resolved in the background by FilesViewModel.prefetchBatchLocalPaths while this item's
    // row is still open for review, so Send can dispatch immediately for items that already
    // resolved instead of waiting on a fresh stub-content read for every file.
    val localPath: String? = null,
)

// Per-row "already exists" / pending-transfer check results, hoisted out of CalibreBatchRow
// (see rowMatchCache below) so they survive LazyColumn recycling instead of resetting every
// time a row scrolls out of and back into the visible window.
private data class RowMatchCache(
    val matchedBookId: Long? = null,
    val matchedBookTitle: String? = null,
    val matchedBookAuthor: String? = null,
    val isUuidMatched: Boolean = false,
    val pendingTransfer: CalibreTransferEntity? = null,
    val pendingTransferChecked: Boolean = false,
    // Inputs the current matchedBook* fields were computed from — lets the LaunchedEffects
    // below skip a redundant metadata.db scan when a row remounts with unchanged inputs.
    val checkedUuid: String? = null,
    val checkedTitle: String? = null,
    val checkedAuthor: String? = null,
    val checkedIncluded: Boolean? = null,
)

/** Collects [FilesViewModel.calibreBatchDraft] in its own small composable rather than at the
 *  top of the (huge) FilesScreen function. Every keystroke in the sheet below replaces the whole
 *  draft list (see FilesViewModel.updateCalibreBatchDraftItem) — collecting the flow inside
 *  FilesScreen itself forced Compose to recompose that entire function on every keystroke, which
 *  ART can't even JIT-compile (it exceeds the method-size limit), so each recomposition ran
 *  interpreted. Editing a ~30-item batch got progressively slower and eventually OOM-crashed the
 *  app from the accumulated GC pressure. Scoping the read to this small composable instead means
 *  only this call site recomposes per keystroke. */
@Composable
fun CalibreBatchDraftHost(viewModel: FilesViewModel) {
    val calibreBatchDraft by viewModel.calibreBatchDraft.collectAsState()
    val isPreparingTransfer by viewModel.isPreparingTransfer.collectAsState()
    val transferPreparationProgress by viewModel.transferPreparationProgress.collectAsState()
    calibreBatchDraft?.let { draft ->
        CalibreBatchConfirmationSheet(
            items = draft,
            isPreviousBatchSending = isPreparingTransfer,
            previousBatchProgress = transferPreparationProgress,
            onDismiss = { viewModel.dismissCalibreBatchDraft() },
            onConfirm = { finalItems ->
                viewModel.sendBatchToCalibre(finalItems)
                viewModel.dismissCalibreBatchDraft()
            },
            onItemChange = { updated -> viewModel.updateCalibreBatchDraftItem(updated) },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            checkPendingTransfer = { fileId, fileName -> viewModel.findPendingTransfer(fileId, fileName) },
            readStubFileSize = { file -> viewModel.readStubFileSize(file) },
            onPreview = { file -> viewModel.previewFile(file) },
            onMirrorSelection = { deselected -> viewModel.deselectFiles(deselected) },
            onReverseSelectionToFileList = { toDeselect, toSelect -> viewModel.reverseFileSelection(toDeselect, toSelect) },
            initialScrollIndex = viewModel.calibreBatchScrollIndex,
            initialScrollOffset = viewModel.calibreBatchScrollOffset,
            onScrollPositionChanged = { index, offset ->
                viewModel.calibreBatchScrollIndex = index
                viewModel.calibreBatchScrollOffset = offset
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreBatchConfirmationSheet(
    items: List<CalibreBatchDraftItem>,
    // Whether some other in-flight "prepare for Calibre" operation is still running — a previous
    // batch's dispatch loop, a single-file send, an archive assembly — anything sharing
    // FilesViewModel.isPreparingTransfer's app-wide counter. Send stays disabled and shows that
    // operation's progress until it clears, since sendBatchToCalibre for this new batch would
    // otherwise run concurrently alongside it, and the user has no way to see how much of the
    // earlier one is left.
    isPreviousBatchSending: Boolean = false,
    previousBatchProgress: Pair<Int, Int>? = null,
    onDismiss: () -> Unit,
    onConfirm: (List<CalibreBatchDraftItem>) -> Unit,
    onItemChange: (CalibreBatchDraftItem) -> Unit,
    checkExists: suspend (String, String) -> Long?,
    checkExistsByUuid: suspend (String) -> CalibreBookMatch?,
    checkPendingTransfer: suspend (Long, String) -> CalibreTransferEntity? = { _, _ -> null },
    readStubFileSize: suspend (PutioFile) -> Long? = { null },
    onPreview: (PutioFile) -> Unit,
    onMirrorSelection: (List<PutioFile>) -> Unit = {},
    onReverseSelectionToFileList: (toDeselect: List<PutioFile>, toSelect: List<PutioFile>) -> Unit = { _, _ -> },
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollPositionChanged: (index: Int, offset: Int) -> Unit = { _, _ -> },
) {
    val includedItems = items.filter { it.included }
    // The sheet opens as a mirror of the Files screen's selection (every item starts included —
    // see startCalibreBatchDraft). Deselecting a row here only affects this draft; "Mirror
    // selection" pushes those deselections back so the underlying file list stays in sync too.
    val deselectedFiles = items.filterNot { it.included }.map { it.file }
    val canSend = includedItems.isNotEmpty() && includedItems.all { it.title.isNotBlank() } && !isPreviousBatchSending
    // Resolved once for the whole list (not per row) so synced stubs' real sizes are fetched a
    // single time and shared, rather than re-fetched by every row's own effect.
    val sizeProgress = rememberSizeProgress(items.map { it.file }, readStubFileSize)
    // Hoisted above the LazyColumn (not inside CalibreBatchRow) so it isn't disposed when a row
    // scrolls out of the visible window — see RowMatchCache.
    val rowMatchCache = remember { mutableStateMapOf<String, RowMatchCache>() }

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

                val batchListState = rememberLazyListState(initialScrollIndex, initialScrollOffset)
                LaunchedEffect(batchListState) {
                    snapshotFlow { batchListState.firstVisibleItemIndex to batchListState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) -> onScrollPositionChanged(index, offset) }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .transientVerticalScrollbar(batchListState),
                    state = batchListState,
                ) {
                    // Composite key, not just item.file.id: two selected files with the same
                    // displayName both resolve to the same "fresh" synced PutioFile in
                    // startCalibreBatchDraft's freshByName lookup, so their file.id can collide —
                    // a bare file.id key then crashes LazyColumn with "Key already used".
                    itemsIndexed(items, key = { index, item -> "$index-${item.file.id}" }) { index, item ->
                        val rowKey = "$index-${item.file.id}"
                        CalibreBatchRow(
                            rowKey = rowKey,
                            rowMatchCache = rowMatchCache,
                            item = item,
                            index = if (items.size > 1) index + 1 else null,
                            sizeBytes = sizeProgress?.sizesById?.get(item.file.id) ?: item.file.size,
                            previousAuthor = items.getOrNull(index - 1)?.author,
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
                            onReverseSelection = { items.forEach { onItemChange(it.copy(included = !it.included)) } },
                        )
                        HorizontalDivider()
                    }

                    // Actions live as the last scrollable row rather than a static footer — they're
                    // only ever taken after reviewing the whole list, and pinning them wasted screen
                    // real estate for the entire review. Scrolling to the bottom before sending is
                    // already the habit, so this just meets that habit instead of fighting it.
                    item {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onConfirm(includedItems) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canSend,
                        ) {
                            Text(
                                if (isPreviousBatchSending) {
                                    val (done, total) = previousBatchProgress ?: (0 to 0)
                                    if (total > 0) "Still sending previous batch ($done/$total)…" else "Still sending previous batch…"
                                } else {
                                    "Send (${includedItems.size})"
                                }
                            )
                        }
                        TextButton(
                            onClick = { onMirrorSelection(deselectedFiles) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = deselectedFiles.isNotEmpty(),
                        ) {
                            Text("Mirror selection to file list (${deselectedFiles.size} to deselect)")
                        }
                        TextButton(
                            onClick = { onReverseSelectionToFileList(includedItems.map { it.file }, deselectedFiles) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = items.isNotEmpty(),
                        ) {
                            Text("Reverse selection to file list")
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

/** A smaller-footprint IconButton for the batch row's dense layout — trades the default 48dp
 *  touch target down to 32dp so several action icons can sit on one compact row. */
@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalibreBatchRow(
    rowKey: String,
    rowMatchCache: androidx.compose.runtime.snapshots.SnapshotStateMap<String, RowMatchCache>,
    item: CalibreBatchDraftItem,
    index: Int?,
    sizeBytes: Long,
    previousAuthor: String?,
    checkExists: suspend (String, String) -> Long?,
    checkExistsByUuid: suspend (String) -> CalibreBookMatch?,
    checkPendingTransfer: suspend (Long, String) -> CalibreTransferEntity?,
    onChange: (CalibreBatchDraftItem) -> Unit,
    onPreview: (PutioFile) -> Unit,
    onSelectAll: () -> Unit,
    onUnselectAll: () -> Unit,
    onUnselectFromHere: () -> Unit,
    onReverseSelection: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    // Backed by rowMatchCache (hoisted above the LazyColumn), not local remember(), so these
    // survive the row being disposed and recomposed as it scrolls out of and back into view.
    val cache = rowMatchCache[rowKey]
    val matchedBookId = cache?.matchedBookId
    val matchedBookTitle = cache?.matchedBookTitle
    val matchedBookAuthor = cache?.matchedBookAuthor
    val isUuidMatched = cache?.isUuidMatched ?: false
    val pendingTransfer = cache?.pendingTransfer
    var showBulkMenu by remember { mutableStateOf(false) }

    // A UUID match takes over title/author (like the single-file dialog) and owns matchedBookId
    // while present; the text-based match below only runs when there's no UUID to match on.
    // Debounced: LaunchedEffect cancels+restarts this block on every keystroke (key = item.uuid),
    // so the delay below means only the keystroke that ends a pause actually reaches the query —
    // rapid typing keeps cancelling it before checkExistsByUuid ever runs. See the debounce
    // comment on the title/author check below for why this matters.
    LaunchedEffect(rowKey, item.uuid) {
        if (item.uuid.isNotBlank()) {
            // Row remounted (e.g. scrolled back into view) with the same uuid already resolved —
            // reuse the cached result instead of re-querying.
            if (rowMatchCache[rowKey]?.checkedUuid == item.uuid) return@LaunchedEffect
            kotlinx.coroutines.delay(400)
            val match = checkExistsByUuid(item.uuid.trim())
            val prev = rowMatchCache[rowKey] ?: RowMatchCache()
            if (match != null) {
                rowMatchCache[rowKey] = prev.copy(
                    matchedBookId = match.id,
                    matchedBookTitle = match.title,
                    matchedBookAuthor = match.author,
                    isUuidMatched = true,
                    checkedUuid = item.uuid,
                )
                if (item.title != match.title || item.author != match.author) {
                    onChange(item.copy(title = match.title, author = match.author))
                }
            } else {
                rowMatchCache[rowKey] = prev.copy(
                    matchedBookId = null,
                    matchedBookTitle = null,
                    matchedBookAuthor = null,
                    isUuidMatched = false,
                    checkedUuid = item.uuid,
                )
            }
        } else {
            val prev = rowMatchCache[rowKey] ?: RowMatchCache()
            rowMatchCache[rowKey] = prev.copy(
                isUuidMatched = false,
                matchedBookTitle = null,
                matchedBookAuthor = null,
                checkedUuid = "",
            )
        }
    }

    // Debounced (see comment above) — checkExists opens the entire Calibre metadata.db and scans
    // every book/author row (Unicode-normalizing each one) to find a title match. Running that on
    // every keystroke while editing a title, across a multi-book batch-send list, made editing
    // measurably slower the further down the list you went and was severe enough to OOM-crash the
    // app on a large-ish library. A 400ms pause-in-typing debounce turns "one full library scan
    // per character typed" into "one scan per row, once you stop typing it." The cache check below
    // does the same for scrolling: a row that remounts with the exact same title/author/included
    // it was last checked with reuses the cached answer instead of re-scanning the library.
    LaunchedEffect(rowKey, item.title, item.author, item.included, item.uuid) {
        if (item.uuid.isNotBlank()) return@LaunchedEffect
        if (item.included && item.title.isNotBlank()) {
            val prev = rowMatchCache[rowKey] ?: RowMatchCache()
            if (prev.checkedTitle == item.title && prev.checkedAuthor == item.author && prev.checkedIncluded == item.included) {
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(400)
            val result = checkExists(item.title, item.author.ifBlank { "Unknown" })
            rowMatchCache[rowKey] = (rowMatchCache[rowKey] ?: RowMatchCache()).copy(
                matchedBookId = result,
                checkedTitle = item.title,
                checkedAuthor = item.author,
                checkedIncluded = item.included,
            )
        } else {
            // Also update checkedIncluded (not just clear matchedBookId) — otherwise re-checking
            // this row later sees checkedIncluded still == true from the last real check, the
            // skip-guard above wrongly treats it as "already checked", and the warning never
            // comes back.
            rowMatchCache[rowKey] = (rowMatchCache[rowKey] ?: RowMatchCache()).copy(
                matchedBookId = null,
                checkedIncluded = item.included,
            )
        }
    }

    LaunchedEffect(rowKey, item.file.id) {
        // Row remounted with this already checked (e.g. scrolled back into view) — skip re-query.
        if (rowMatchCache[rowKey]?.pendingTransferChecked == true) return@LaunchedEffect
        val pending = checkPendingTransfer(item.file.syncedFileId, item.file.displayName)
        rowMatchCache[rowKey] = (rowMatchCache[rowKey] ?: RowMatchCache()).copy(
            pendingTransfer = pending,
            pendingTransferChecked = true,
        )
        // Auto-deselect: an item already in flight must not be resubmitted just because it
        // happened to be checked when the batch sheet opened — the checkbox alone previously
        // let it through with only a visual warning next to it, easy to miss in a long batch.
        if (pending != null && item.included) {
            onChange(item.copy(included = false))
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                    DropdownMenuItem(
                        text = { Text("Reverse selection") },
                        onClick = { showBulkMenu = false; onReverseSelection() },
                    )
                }
            }
            if (item.file.isSynced) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = if (item.localPath != null) "File info resolved" else "Resolving file info…",
                    tint = if (item.localPath != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = if (sizeBytes > 0) "${item.file.displayName} [${formatByteSize(sizeBytes)}]" else item.file.displayName,
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onPreview(item.file) },
            )
        }

        if (pendingTransfer != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.height(16.dp).width(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Already has a pending transfer (${pendingTransfer!!.status}) — deselected",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (item.included) {
            if (matchedBookId != null) {
                Spacer(Modifier.height(6.dp))
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

            // A UUID match only targets an existing book for a new format — the daemon never
            // writes title/author back for it (see process_book_batch in putz_manager.py), so
            // editing these fields once matched would be misleading. Lock them, mirroring the
            // single-file dialog.
            Spacer(Modifier.height(6.dp))
            CompactOutlinedTextField(
                value = item.title,
                onValueChange = { onChange(item.copy(title = it)) },
                label = "Title",
                modifier = Modifier.fillMaxWidth(),
                enabled = !isUuidMatched,
                dense = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = if (!isUuidMatched) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompactIconButton(
                                onClick = { onChange(item.copy(title = item.file.displayName.substringBeforeLast('.'))) },
                                contentDescription = "Load filename as title",
                                icon = Icons.Default.FileOpen,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                            CompactIconButton(
                                onClick = { onChange(item.copy(title = MetadataUtils.fixTitleCapitalization(item.title))) },
                                contentDescription = "Fix title capitalisation",
                                icon = Icons.Default.AutoFixHigh,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                } else null,
            )
            Spacer(Modifier.height(6.dp))
            // Highlights an author that repeats the immediately preceding row's author (e.g. a
            // multi-volume set added in one batch), using the same salmon/dark-foreground pairing
            // as the primary "Send" action, so a long run of identical authors is easy to spot at
            // a glance while scrolling.
            val isRepeatAuthor = previousAuthor != null &&
                item.author.isNotBlank() &&
                item.author.trim() == previousAuthor.trim()
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactOutlinedTextField(
                    value = item.author,
                    onValueChange = { onChange(item.copy(author = it)) },
                    label = "Author",
                    placeholder = "Unknown",
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isRepeatAuthor) {
                                Modifier.background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.extraSmall,
                                )
                            } else Modifier
                        ),
                    enabled = !isUuidMatched,
                    dense = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                CompactIconButton(
                    onClick = { onChange(item.copy(title = item.author, author = item.title)) },
                    contentDescription = "Swap title and author",
                    icon = Icons.Default.SwapVert,
                    enabled = !isUuidMatched,
                )
                if (item.author.count { it == ',' } == 1) {
                    CompactIconButton(
                        onClick = {
                            val (surname, given) = item.author.split(",", limit = 2)
                            val fixed = "${given.trim()} ${surname.trim()}"
                                .split(" ")
                                .joinToString(" ") { word ->
                                    word.lowercase().replaceFirstChar { it.uppercaseChar() }
                                }
                            onChange(item.copy(author = fixed))
                        },
                        contentDescription = "Swap surname and given name",
                        icon = Icons.Default.PersonSearch,
                        enabled = !isUuidMatched,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // UUID/tags/toggles are rarely touched, so they start collapsed behind this chevron —
            // keeping a multi-file batch scannable down to just title/author per row — but default
            // to expanded whenever a row already carries a non-default value in one of them (e.g.
            // a draft restored from a previous edit), so nothing is silently hidden from view.
            var expanded by remember(item.file.id) {
                mutableStateOf(
                    item.uuid.isNotBlank() || item.tags.isNotBlank() || item.isProtected || item.isAltVersion,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide UUID/tags/options" else "Show UUID/tags/options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactOutlinedTextField(
                        value = item.uuid,
                        onValueChange = { onChange(item.copy(uuid = it)) },
                        label = "Book UUID (Optional)",
                        modifier = Modifier.weight(1f),
                        placeholder = "Directly target an existing book",
                        dense = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    CompactIconButton(
                        onClick = {
                            val pasted = clipboardManager.getText()?.text.orEmpty().trim()
                            if (pasted.isNotEmpty()) onChange(item.copy(uuid = pasted))
                        },
                        contentDescription = "Paste UUID from clipboard",
                        icon = Icons.Default.ContentPaste,
                    )
                }

                Spacer(Modifier.height(6.dp))
                CompactOutlinedTextField(
                    value = item.tags,
                    onValueChange = { onChange(item.copy(tags = it)) },
                    label = "Tags (optional)",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Programming, Python, Reference",
                    dense = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Encrypt",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = item.isProtected,
                        onCheckedChange = { onChange(item.copy(isProtected = it)) },
                        modifier = Modifier.scale(0.75f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Alt. version",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = item.isAltVersion,
                        onCheckedChange = { onChange(item.copy(isAltVersion = it)) },
                        modifier = Modifier.scale(0.75f),
                    )
                }
            }

        }
    }
}
