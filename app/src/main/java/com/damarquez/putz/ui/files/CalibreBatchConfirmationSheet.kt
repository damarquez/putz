package com.damarquez.putz.ui.files

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.damarquez.putz.ui.components.CompactOutlinedTextField
import com.damarquez.putz.util.MetadataUtils

/** One row of the batch send-to-Calibre draft: a selected file plus its editable title/author. */
data class CalibreBatchDraftItem(
    val file: PutioFile,
    val title: String,
    val author: String,
    val included: Boolean = true,
    val isProtected: Boolean = false,
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
                            checkPendingTransfer = checkPendingTransfer,
                            onChange = onItemChange,
                            onPreview = onPreview,
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

@Composable
private fun CalibreBatchRow(
    item: CalibreBatchDraftItem,
    index: Int?,
    checkExists: suspend (String, String) -> Long?,
    checkPendingTransfer: suspend (Long, String) -> CalibreTransferEntity?,
    onChange: (CalibreBatchDraftItem) -> Unit,
    onPreview: (PutioFile) -> Unit,
) {
    val context = LocalContext.current
    var matchedBookId by remember(item.file.id) { mutableStateOf<Long?>(null) }
    var pendingTransfer by remember(item.file.id) { mutableStateOf<CalibreTransferEntity?>(null) }

    LaunchedEffect(item.title, item.author, item.included) {
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
            Checkbox(
                checked = item.included,
                onCheckedChange = { onChange(item.copy(included = it)) },
            )
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
            Spacer(Modifier.height(4.dp))
            CompactOutlinedTextField(
                value = item.title,
                onValueChange = { onChange(item.copy(title = it)) },
                label = "Title",
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = {
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
                },
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactOutlinedTextField(
                    value = item.author,
                    onValueChange = { onChange(item.copy(author = it)) },
                    label = "Author",
                    placeholder = "Unknown",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { onChange(item.copy(title = item.author, author = item.title)) },
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Swap title and author",
                    )
                }
            }

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
                        .clickable { openCalibreAnywhereSearch(context, item.title) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(16.dp).width(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Might already exist in Calibre! (ID: #$matchedBookId)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
