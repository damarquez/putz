package com.damarquez.putz.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.util.MetadataUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePdfPackSheet(
    imageFiles: List<PutioFile>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (selectedFiles: List<PutioFile>) -> Unit,
) {
    var caseSensitiveSort by remember(imageFiles) { mutableStateOf(false) }
    var orderedFiles by remember(imageFiles, caseSensitiveSort) {
        mutableStateOf(MetadataUtils.sortByName(imageFiles, caseSensitiveSort) { it.displayName })
    }
    var checkedIds by remember(imageFiles) { mutableStateOf(imageFiles.map { it.id }.toSet()) }
    val selectedFiles = orderedFiles.filter { it.id in checkedIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Select images for PDF",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Pages are assembled in the listed order — use ▲▼ to reorder.",
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
                    text = "${selectedFiles.size} of ${imageFiles.size} images selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        checkedIds = if (checkedIds.size == imageFiles.size) {
                            emptySet()
                        } else {
                            imageFiles.map { it.id }.toSet()
                        }
                    },
                ) {
                    Text(if (checkedIds.size == imageFiles.size) "Deselect all" else "Select all")
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(orderedFiles, key = { _, file -> file.id }) { index, file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = file.id in checkedIds,
                            onCheckedChange = { checked ->
                                checkedIds = if (checked) checkedIds + file.id else checkedIds - file.id
                            },
                        )
                        Text(
                            text = file.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ReorderArrowButton(
                            icon = Icons.Default.ArrowUpward,
                            contentDescription = "Move up",
                            enabled = index > 0,
                            onClick = {
                                if (index > 0) {
                                    orderedFiles = orderedFiles.toMutableList().also {
                                        val tmp = it[index]; it[index] = it[index - 1]; it[index - 1] = tmp
                                    }
                                }
                            },
                            onLongClick = {
                                if (index > 0) {
                                    orderedFiles = orderedFiles.toMutableList().also {
                                        it.add(0, it.removeAt(index))
                                    }
                                }
                            },
                        )
                        ReorderArrowButton(
                            icon = Icons.Default.ArrowDownward,
                            contentDescription = "Move down",
                            enabled = index < orderedFiles.lastIndex,
                            onClick = {
                                if (index < orderedFiles.lastIndex) {
                                    orderedFiles = orderedFiles.toMutableList().also {
                                        val tmp = it[index]; it[index] = it[index + 1]; it[index + 1] = tmp
                                    }
                                }
                            },
                            onLongClick = {
                                if (index < orderedFiles.lastIndex) {
                                    orderedFiles = orderedFiles.toMutableList().also {
                                        it.add(it.removeAt(index))
                                    }
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onConfirm(selectedFiles) },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedFiles.isNotEmpty(),
            ) {
                Text("Continue")
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}
