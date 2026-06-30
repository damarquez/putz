package com.damarquez.putz.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.ui.components.CompactOutlinedTextField

data class AssemblyOverride(
    val title: String,
    val author: String,
    val uuid: String?,
    val tags: String?,
    val isProtected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyAppendSheet(
    formatDisplayName: String,
    assembly: CalibreTransferEntity,
    assemblyIsProtected: Boolean,
    isArchive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (isAltVersion: Boolean, archiveMode: String?, override: AssemblyOverride?) -> Unit,
) {
    var overrideEnabled by remember { mutableStateOf(false) }
    var overrideTitle by remember { mutableStateOf(assembly.title) }
    var overrideAuthor by remember { mutableStateOf(assembly.author) }
    var overrideUuid by remember { mutableStateOf(assembly.calibreBookUuid ?: "") }
    var overrideTags by remember { mutableStateOf(assembly.tags ?: "") }
    var overrideProtected by remember { mutableStateOf(assemblyIsProtected) }

    var isAltVersion by remember { mutableStateOf(false) }
    var archiveMode by remember { mutableStateOf("default") }

    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        confirmButton = {},
        title = { Text("Add format to assembly", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                Text(
                    text = formatDisplayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Adding to:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!overrideEnabled) {
                        TextButton(onClick = { overrideEnabled = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Override book details",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    } else {
                        Text(
                            text = "Editing book details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                CompactOutlinedTextField(
                    value = if (overrideEnabled) overrideTitle else assembly.title,
                    onValueChange = { overrideTitle = it },
                    label = "Title",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = overrideEnabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Spacer(Modifier.height(8.dp))

                CompactOutlinedTextField(
                    value = if (overrideEnabled) overrideAuthor else assembly.author,
                    onValueChange = { overrideAuthor = it },
                    label = "Author",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = overrideEnabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactOutlinedTextField(
                        value = if (overrideEnabled) overrideUuid else (assembly.calibreBookUuid ?: ""),
                        onValueChange = { overrideUuid = it },
                        label = "Book UUID",
                        modifier = Modifier.weight(1f),
                        enabled = overrideEnabled,
                        placeholder = "Target a specific book",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    if (overrideEnabled) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                val pasted = clipboardManager.getText()?.text.orEmpty().trim()
                                if (pasted.isNotEmpty()) overrideUuid = pasted
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste UUID from clipboard",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                CompactOutlinedTextField(
                    value = if (overrideEnabled) overrideTags else (assembly.tags ?: ""),
                    onValueChange = { overrideTags = it },
                    label = "Tags",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = overrideEnabled,
                    placeholder = "Programming, Python, Reference",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )

                // Protected: show when override is active or when the assembly is currently protected
                // (hidden when false + read-only to avoid cluttering the confirmation view)
                if (overrideEnabled || assemblyIsProtected) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Encrypt book",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Daemon encrypts all formats before adding to Calibre",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = if (overrideEnabled) overrideProtected else assemblyIsProtected,
                            onCheckedChange = { if (overrideEnabled) overrideProtected = it },
                            enabled = overrideEnabled,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    text = "Format options",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Alternative version",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Append _bkp to extension (e.g. pdf → pdf_bkp)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isAltVersion, onCheckedChange = { isAltVersion = it })
                }

                if (isArchive) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Archive mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            selected = archiveMode == "default",
                            onClick = { archiveMode = "default" },
                        ) { Text("Default") }
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            selected = archiveMode == "audio",
                            onClick = { archiveMode = "audio" },
                        ) { Text("Audio") }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        val override = if (overrideEnabled) AssemblyOverride(
                            title = overrideTitle.trim(),
                            author = overrideAuthor.trim().ifBlank { "Unknown" },
                            uuid = overrideUuid.trim().ifBlank { null },
                            tags = overrideTags.trim().ifBlank { null },
                            isProtected = overrideProtected,
                        ) else null
                        onConfirm(isAltVersion, if (isArchive) archiveMode else null, override)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !overrideEnabled || overrideTitle.isNotBlank(),
                ) {
                    Text("Add format")
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}

/** Parses the `protected` flag from the first item in an assembled transfer's batchData.
 *  Returns false if there is no batchData or no items. */
fun CalibreTransferEntity.assemblyIsProtected(): Boolean {
    val data = batchData ?: return false
    return try {
        val json = org.json.JSONArray(data)
        if (json.length() == 0) false
        else json.getJSONObject(0).optBoolean("protected", false)
    } catch (_: Exception) {
        false
    }
}
