package com.damarquez.putz.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreConfirmationSheet(
    displayName: String,
    initialTitle: String,
    initialAuthor: String,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, archiveMode: String?, assembleBook: Boolean, isAltVersion: Boolean, calibreBookId: Long?) -> Unit,
    checkExists: suspend (String, String) -> Long?,
    isArchive: Boolean = false,
    forceAssemble: Boolean = false,
    isReplaceCover: Boolean = false,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    var matchedBookId by remember { mutableStateOf<Long?>(null) }
    var archiveMode by remember { mutableStateOf("default") }
    var assembleBook by remember { mutableStateOf(forceAssemble) }
    var isAltVersion by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(title, author) {
        if (title.isNotBlank() && author.isNotBlank()) {
            matchedBookId = checkExists(title, author)
        } else {
            matchedBookId = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .imePadding(),
        ) {
            Text(
                text = if (isReplaceCover) "Replace Book Cover" else "Send to Calibre",
                style = MaterialTheme.typography.titleLarge,
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (matchedBookId != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val intent = Intent("com.damarquez.calibreanywhere.SEARCH_TITLE").apply {
                                    setPackage("com.damarquez.calibreanywhere")
                                    putExtra("com.damarquez.calibreanywhere.extra.SEARCH_QUERY", title)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val deepLink = "calibreanywhere://search?q=${Uri.encode(title)}"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e2: Exception) {
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isReplaceCover) Icons.Default.FileOpen else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isReplaceCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isReplaceCover) "Matched book ID: #$matchedBookId" else "This book might already exist in Calibre! (ID: #$matchedBookId)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        ),
                        color = if (isReplaceCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            } else if (isReplaceCover) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Book not found in library!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            title = displayName.substringBeforeLast('.')
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Load filename as title",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { val tmp = title; title = author; author = tmp }) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Swap title and author",
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    placeholder = { Text("Unknown") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (!isReplaceCover || matchedBookId != null) {
                            onConfirm(title.trim(), author.trim().ifBlank { "Unknown" }, if (isArchive) archiveMode else null, assembleBook, isAltVersion, matchedBookId)
                        }
                    }),
                )
                if (author.count { it == ',' } == 1) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            val (surname, given) = author.split(",", limit = 2)
                            author = "${given.trim()} ${surname.trim()}"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonSearch,
                            contentDescription = "Swap surname and given name",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (!forceAssemble && !isReplaceCover) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Alternative version",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Rename extension to '_bkp'",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = isAltVersion,
                        onCheckedChange = { isAltVersion = it }
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Assemble book",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Add to list but don't send immediately",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = assembleBook,
                        onCheckedChange = { assembleBook = it }
                    )
                }
            }

            if (isArchive && !isReplaceCover) {
                Spacer(Modifier.height(20.dp))
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
                onClick = { onConfirm(title.trim(), author.trim().ifBlank { "Unknown" }, if (isArchive) archiveMode else null, assembleBook, isAltVersion, matchedBookId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && (!isReplaceCover || matchedBookId != null),
            ) {
                Text(
                    when {
                        isReplaceCover -> "Replace Cover"
                        assembleBook -> "Assemble Book"
                        else -> "Confirm & Send"
                    }
                )
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
