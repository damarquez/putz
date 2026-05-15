package com.damarquez.putz.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookPackSheet(
    audioFiles: List<PutioFile>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (selectedFiles: List<PutioFile>) -> Unit,
) {
    var checkedIds by remember(audioFiles) { mutableStateOf(audioFiles.map { it.id }.toSet()) }
    val selectedFiles = audioFiles.filter { it.id in checkedIds }

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
                text = "Select files for audiobook",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Files are sorted by name — each becomes one chapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "${selectedFiles.size} of ${audioFiles.size} files selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)) {
                items(audioFiles, key = { it.id }) { file ->
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
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
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
