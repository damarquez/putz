package com.damarquez.putz.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.PutioFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlexFolderPickerSheet(
    state: LanFolderPickerState,
    onDismiss: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateInto: (PutioFile) -> Unit,
    onSelect: (relativePath: String) -> Unit,
    /** When non-null the sheet is in file-pick mode: video files are listed and tapping one calls this. */
    onFileSelected: ((folder: PutioFile) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isFilePicker = onFileSelected != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canNavigateUp) {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
                Text(
                    text = state.breadcrumb,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                if (!isFilePicker) {
                    TextButton(
                        onClick = { onSelect(state.relativePath) },
                        enabled = !state.isLoading,
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Select")
                    }
                }
            }

            HorizontalDivider()

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.folders.isEmpty() && state.files.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isFilePicker) "No video files or subfolders here" else "No subfolders or files here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.folders, key = { it.id }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateInto(folder) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = folder.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (state.files.isNotEmpty()) {
                            if (state.folders.isNotEmpty()) {
                                item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
                            }
                            items(state.files, key = { "file_${it.id}" }) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isFilePicker) Modifier.clickable { onFileSelected!!(file) }
                                            else Modifier
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoFile,
                                        contentDescription = null,
                                        tint = if (isFilePicker) MaterialTheme.colorScheme.secondary
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isFilePicker) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
