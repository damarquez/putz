package com.damarquez.putz.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun PlexampConfirmationSheet(
    displayName: String,
    initialArtistName: String,
    initialAlbumName: String,
    selectedDestPath: String,
    onDismiss: () -> Unit,
    onBrowse: () -> Unit,
    onConfirm: (artistName: String, albumName: String, destPath: String, createFolder: Boolean) -> Unit,
) {
    var artistName by remember { mutableStateOf(initialArtistName) }
    var albumName by remember { mutableStateOf(initialAlbumName) }
    var createFolder by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        confirmButton = {},
        title = { Text("Send to Plexamp", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = artistName,
                    onValueChange = { artistName = it },
                    label = { Text("Artist name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    label = { Text("Album name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Create folder", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (createFolder)
                                "${artistName.trim().ifBlank { "Artist" }} / ${albumName.trim().ifBlank { "Album" }}"
                            else
                                "Place files directly into selected folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = createFolder, onCheckedChange = { createFolder = it })
                }

                if (!createFolder) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Destination",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (selectedDestPath.isNotBlank()) "/$selectedDestPath" else "No folder selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectedDestPath.isNotBlank())
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onBrowse) {
                            Text("Browse…")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { onConfirm(artistName.trim(), albumName.trim(), selectedDestPath, createFolder) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = artistName.isNotBlank() && albumName.isNotBlank() && (createFolder || selectedDestPath.isNotBlank()),
                ) {
                    Text("Send to Plexamp")
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}
