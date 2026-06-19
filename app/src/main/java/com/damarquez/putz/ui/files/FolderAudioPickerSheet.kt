package com.damarquez.putz.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.util.MetadataUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderAudioPickerSheet(
    state: FolderAudioPickerState,
    onDismiss: () -> Unit,
    onConfirm: (selectedFiles: List<FolderAudioFile>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val folderName = when (state) {
            is FolderAudioPickerState.Loading -> state.folderName
            is FolderAudioPickerState.Error -> state.folderName
            is FolderAudioPickerState.Ready -> state.folderName
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Create M4B from folder",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = folderName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            when (state) {
                is FolderAudioPickerState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Scanning audio files…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is FolderAudioPickerState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }

                is FolderAudioPickerState.Ready -> {
                    ReadyContent(state = state, onDismiss = onDismiss, onConfirm = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReadyContent(
    state: FolderAudioPickerState.Ready,
    onDismiss: () -> Unit,
    onConfirm: (List<FolderAudioFile>) -> Unit,
) {
    var caseSensitiveSort by remember(state.files) { mutableStateOf(false) }
    val sortedFiles = remember(state.files, caseSensitiveSort) {
        MetadataUtils.sortByName(state.files, caseSensitiveSort) { it.relativePath }
    }
    val hasSubfolders = sortedFiles.any { it.relativePath.contains('/') }
    var checkedPaths by remember(state.files) {
        mutableStateOf(state.files.map { it.relativePath }.toSet())
    }
    val selected = sortedFiles.filter { it.relativePath in checkedPaths }

    Text(
        text = "Files are sorted by folder then name — each becomes one chapter.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Case-sensitive sort",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = caseSensitiveSort,
            onCheckedChange = { caseSensitiveSort = it },
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${selected.size} of ${state.files.size} files selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                checkedPaths = if (checkedPaths.size == state.files.size) {
                    emptySet()
                } else {
                    state.files.map { it.relativePath }.toSet()
                }
            },
        ) {
            Text(if (checkedPaths.size == state.files.size) "Deselect all" else "Select all")
        }
    }
    Spacer(Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        itemsIndexed(sortedFiles, key = { _, file -> file.relativePath }) { index, folderFile ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = folderFile.relativePath in checkedPaths,
                    onCheckedChange = { checked ->
                        checkedPaths = if (checked) {
                            checkedPaths + folderFile.relativePath
                        } else {
                            checkedPaths - folderFile.relativePath
                        }
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    if (hasSubfolders) {
                        val slash = folderFile.relativePath.lastIndexOf('/')
                        if (slash >= 0) {
                            Text(
                                text = folderFile.relativePath.substring(0, slash),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    CollapsedFileNameText(
                        name = folderFile.file.displayName,
                        previousName = sortedFiles.getOrNull(index - 1)?.file?.displayName,
                        nextName = sortedFiles.getOrNull(index + 1)?.file?.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = { onConfirm(selected) },
        modifier = Modifier.fillMaxWidth(),
        enabled = selected.isNotEmpty(),
    ) {
        Text("Continue")
    }

    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}
