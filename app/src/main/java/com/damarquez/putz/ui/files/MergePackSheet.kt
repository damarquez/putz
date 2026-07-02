package com.damarquez.putz.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.damarquez.putz.data.model.PutioFile

/** Shown while a folder/archive-dir merge trigger's full-tree tally scan is in flight
 * (put.io-backed folders only — see [MergeChoiceState.Scanning]). */
@Composable
fun MergeScanningDialog(folderName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scanning \"$folderName\"…") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown when a folder/archive-dir merge trigger's tally scan fails. */
@Composable
fun MergeScanErrorDialog(folderName: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge \"$folderName\"") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * "What content type" choice for a folder merge trigger — which engine to run, since a
 * folder may contain more than one mergeable file type. Shown before the process-mode
 * question (flatten vs. subfolders-as-chapters), after [scan] has already tallied the whole
 * tree — only content types with at least one match are offered, each with its count. Uses a
 * plain column of options (rather than AlertDialog's 2-slot confirm/dismiss buttons) so it
 * scales past two engines.
 */
@Composable
fun MergeContentTypeChoiceDialog(
    folderName: String,
    scan: FolderScanResult,
    onDismiss: () -> Unit,
    onChoose: (MergeContentType) -> Unit,
) {
    val available = MergeContentType.entries.filter { scan.countFor(it) > 0 }
    val summary = buildString {
        append(scan.extensionTally().joinToString(", ") { (ext, count) -> "${ext.ifEmpty { "?" }} ($count)" })
        if (scan.subfolderCount > 0) append(", in ${scan.subfolderCount} subfolder${if (scan.subfolderCount == 1) "" else "s"}")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge \"$folderName\"") },
        text = {
            Column {
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                if (available.isEmpty()) {
                    Text("No mergeable files found in this folder.")
                } else {
                    Text("What should be merged?")
                    Spacer(Modifier.height(8.dp))
                    for (type in available) {
                        TextButton(onClick = { onChoose(type) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${type.label} (${scan.countFor(type)})", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * "What process" choice for a folder merge trigger: flatten everything into one
 * unchaptered list, or treat each immediate subfolder as a chapter. Shown before the
 * recursive scan starts.
 */
@Composable
fun MergeProcessChoiceDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onChoose: (MergeProcessMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge \"$folderName\"") },
        text = { Text("Combine all files into one, or treat each subfolder as its own chapter?") },
        confirmButton = {
            TextButton(onClick = { onChoose(MergeProcessMode.SUBFOLDERS_AS_CHAPTERS) }) {
                Text("Subfolders as chapters")
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(MergeProcessMode.FLATTEN) }) {
                Text("Flatten all")
            }
        },
    )
}

/**
 * Generic confirm/reorder sheet for the merge framework's folder trigger. Handles both
 * process modes from [MergePickerState]: a flat reorderable/selectable file list
 * ([MergePickerState.ReadyFlat]) or a reorderable chapter list, one row per subfolder
 * ([MergePickerState.ReadyGrouped]). [MergePickerState.Loading]/[MergePickerState.Error]
 * render a small status sheet instead.
 */
@Composable
fun MergePackSheet(
    state: MergePickerState,
    onDismiss: () -> Unit,
    onConfirmFlat: (List<MergeCandidateFile>) -> Unit,
    onConfirmGrouped: (List<MergeCandidateGroup>) -> Unit,
    readStubFileSize: suspend (PutioFile) -> Long?,
    extraControls: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            when (state) {
                is MergePickerState.Loading -> MergeStatusContent("Scanning \"${state.folderName}\"…") { CircularProgressIndicator() }
                is MergePickerState.Error -> MergeStatusContent(state.message) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                is MergePickerState.ReadyFlat -> MergeFlatContent(state.files, onDismiss, onConfirmFlat, readStubFileSize, extraControls)
                is MergePickerState.ReadyGrouped -> MergeGroupedContent(state.groups, onDismiss, onConfirmGrouped, readStubFileSize, extraControls)
            }
        }
    }
}

@Composable
private fun MergeStatusContent(message: String, action: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
private fun MergeFlatContent(
    files: List<MergeCandidateFile>,
    onDismiss: () -> Unit,
    onConfirm: (List<MergeCandidateFile>) -> Unit,
    readStubFileSize: suspend (PutioFile) -> Long?,
    extraControls: (@Composable () -> Unit)? = null,
) {
    var orderedFiles by remember(files) { mutableStateOf(files) }
    var checkedPaths by remember(files) { mutableStateOf(files.map { it.relativePath }.toSet()) }
    val selectedFiles = orderedFiles.filter { it.relativePath in checkedPaths }
    var collapseNames by remember { mutableStateOf(true) }
    val sizeProgress = rememberSizeProgress(files.map { it.file }, readStubFileSize)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(text = "Select files to merge", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Pages are assembled in the listed order — use ▲▼ to reorder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${selectedFiles.size} of ${files.size} files selected" + sizeProgressSuffix(sizeProgress, selectedFiles.map { it.file }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                checkedPaths = if (checkedPaths.size == files.size) emptySet() else files.map { it.relativePath }.toSet()
            }) {
                Text(if (checkedPaths.size == files.size) "Deselect all" else "Select all")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

        extraControls?.invoke()

        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(orderedFiles, key = { _, f -> f.relativePath }) { index, candidate ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = candidate.relativePath in checkedPaths,
                        onCheckedChange = { checked ->
                            checkedPaths = if (checked) checkedPaths + candidate.relativePath else checkedPaths - candidate.relativePath
                        },
                    )
                    if (collapseNames) {
                        CollapsedFileNameText(
                            name = candidate.relativePath,
                            previousName = orderedFiles.getOrNull(index - 1)?.relativePath,
                            nextName = orderedFiles.getOrNull(index + 1)?.relativePath,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = candidate.relativePath,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ReorderArrowButton(
                        icon = Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        enabled = index > 0,
                        onClick = {
                            if (index > 0) orderedFiles = orderedFiles.toMutableList().also {
                                val tmp = it[index]; it[index] = it[index - 1]; it[index - 1] = tmp
                            }
                        },
                        onLongClick = {
                            if (index > 0) orderedFiles = orderedFiles.toMutableList().also { it.add(0, it.removeAt(index)) }
                        },
                    )
                    ReorderArrowButton(
                        icon = Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        enabled = index < orderedFiles.lastIndex,
                        onClick = {
                            if (index < orderedFiles.lastIndex) orderedFiles = orderedFiles.toMutableList().also {
                                val tmp = it[index]; it[index] = it[index + 1]; it[index + 1] = tmp
                            }
                        },
                        onLongClick = {
                            if (index < orderedFiles.lastIndex) orderedFiles = orderedFiles.toMutableList().also { it.add(it.removeAt(index)) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = { onConfirm(selectedFiles) }, modifier = Modifier.fillMaxWidth(), enabled = selectedFiles.isNotEmpty()) {
            Text("Continue")
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun MergeGroupedContent(
    groups: List<MergeCandidateGroup>,
    onDismiss: () -> Unit,
    onConfirm: (List<MergeCandidateGroup>) -> Unit,
    readStubFileSize: suspend (PutioFile) -> Long?,
    extraControls: (@Composable () -> Unit)? = null,
) {
    var orderedGroups by remember(groups) { mutableStateOf(groups) }
    val allFiles = remember(groups) { groups.flatMap { it.files } }
    val sizeProgress = rememberSizeProgress(allFiles.map { it.file }, readStubFileSize)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(text = "Order chapters", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Each subfolder becomes one chapter, in the listed order — use ▲▼ to reorder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${allFiles.size} files" + sizeProgressSuffix(sizeProgress, allFiles.map { it.file }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        extraControls?.invoke()

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(orderedGroups, key = { _, g -> g.label }) { index, group ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${group.files.size} file(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReorderArrowButton(
                        icon = Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        enabled = index > 0,
                        onClick = {
                            if (index > 0) orderedGroups = orderedGroups.toMutableList().also {
                                val tmp = it[index]; it[index] = it[index - 1]; it[index - 1] = tmp
                            }
                        },
                        onLongClick = {
                            if (index > 0) orderedGroups = orderedGroups.toMutableList().also { it.add(0, it.removeAt(index)) }
                        },
                    )
                    ReorderArrowButton(
                        icon = Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        enabled = index < orderedGroups.lastIndex,
                        onClick = {
                            if (index < orderedGroups.lastIndex) orderedGroups = orderedGroups.toMutableList().also {
                                val tmp = it[index]; it[index] = it[index + 1]; it[index + 1] = tmp
                            }
                        },
                        onLongClick = {
                            if (index < orderedGroups.lastIndex) orderedGroups = orderedGroups.toMutableList().also { it.add(it.removeAt(index)) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(onClick = { onConfirm(orderedGroups) }, modifier = Modifier.fillMaxWidth(), enabled = orderedGroups.isNotEmpty()) {
            Text("Continue")
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
