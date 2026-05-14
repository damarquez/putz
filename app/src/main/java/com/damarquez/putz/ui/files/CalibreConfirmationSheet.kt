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
import com.damarquez.putz.data.repository.CalibreBookMatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreConfirmationSheet(
    displayName: String,
    initialTitle: String,
    initialAuthor: String,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, archiveMode: String?, assembleBook: Boolean, isAltVersion: Boolean, calibreBookId: Long?, calibreBookUuid: String?, comments: String?, tags: String?) -> Unit,
    checkExists: suspend (String, String) -> Long?,
    checkExistsByUuid: suspend (String) -> CalibreBookMatch?,
    isArchive: Boolean = false,
    forceAssemble: Boolean = false,
    isReplaceCover: Boolean = false,
    isUpdateComments: Boolean = false,
    initialUuid: String = "",
    initialComments: String = "",
    initialTags: String = "",
    autoAddTags: String? = null,
    includeComments: Boolean = true,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    var uuid by remember { mutableStateOf(initialUuid) }
    var comments by remember { mutableStateOf(initialComments) }
    var tags by remember { mutableStateOf(initialTags) }

    LaunchedEffect(initialUuid) {
        if (initialUuid.isNotBlank()) {
            uuid = initialUuid
        }
    }

    LaunchedEffect(initialComments) {
        if (initialComments.isNotBlank()) {
            comments = initialComments
        }
    }

    LaunchedEffect(initialTags) {
        if (initialTags.isNotBlank()) {
            tags = initialTags
        }
    }

    var matchedBookId by remember { mutableStateOf<Long?>(null) }
    var matchedBookTitle by remember { mutableStateOf<String?>(null) }
    var matchedBookAuthor by remember { mutableStateOf<String?>(null) }
    var isUuidMatched by remember { mutableStateOf(false) }
    var isUuidValidating by remember { mutableStateOf(false) }

    var archiveMode by remember { mutableStateOf("default") }
    var assembleBook by remember { mutableStateOf(forceAssemble) }
    var isAltVersion by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val titleAuthorLocked = isReplaceCover
    val requiresUuidMatch = isReplaceCover

    LaunchedEffect(uuid, autoAddTags) {
        if (uuid.isNotBlank()) {
            isUuidValidating = true
            val match = checkExistsByUuid(uuid.trim())
            if (match != null) {
                matchedBookId = match.id
                matchedBookTitle = match.title
                matchedBookAuthor = match.author
                isUuidMatched = true
                if (isReplaceCover || title.isBlank()) {
                    title = match.title
                }
                if (isReplaceCover || author.isBlank()) {
                    author = match.author
                }
                if (tags.isBlank() || !autoAddTags.isNullOrBlank()) {
                    tags = mergeTags(match.tags, autoAddTags)
                }
            } else {
                matchedBookId = null
                matchedBookTitle = null
                matchedBookAuthor = null
                isUuidMatched = false
            }
            isUuidValidating = false
        } else {
            isUuidMatched = false
            matchedBookId = null
            matchedBookTitle = null
            matchedBookAuthor = null
        }
    }

    LaunchedEffect(title, author, uuid) {
        if (uuid.isBlank() && !requiresUuidMatch && title.isNotBlank() && author.isNotBlank()) {
            matchedBookId = checkExists(title, author)
            matchedBookTitle = null
            matchedBookAuthor = null
        } else if (uuid.isBlank()) {
            matchedBookId = null
            matchedBookTitle = null
            matchedBookAuthor = null
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
                text = when {
                    isReplaceCover -> "Replace Book Cover"
                    isUpdateComments && includeComments -> "Update Book Comments"
                    isUpdateComments -> "Update Book Tags"
                    else -> "Send to Calibre"
                },
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
                                val searchTitle = matchedBookTitle ?: title
                                val intent = Intent("com.damarquez.calibreanywhere.SEARCH_TITLE").apply {
                                    setPackage("com.damarquez.calibreanywhere")
                                    putExtra("com.damarquez.calibreanywhere.extra.SEARCH_QUERY", searchTitle)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val searchTitle = matchedBookTitle ?: title
                                    val deepLink = "calibreanywhere://search?q=${Uri.encode(searchTitle)}"
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
                        imageVector = if (isReplaceCover || isUpdateComments || isUuidMatched) Icons.Default.FileOpen else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isReplaceCover || isUpdateComments || isUuidMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            isUuidMatched -> "Matched: $matchedBookTitle by $matchedBookAuthor (ID: #$matchedBookId)"
                            isReplaceCover || isUpdateComments -> "Matched book ID: #$matchedBookId"
                            else -> "This book might already exist in Calibre! (ID: #$matchedBookId)"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        ),
                        color = if (isReplaceCover || isUpdateComments || isUuidMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            } else if (isReplaceCover || isUpdateComments || (uuid.isNotBlank() && !isUuidValidating)) {
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
                        text = if (uuid.isNotBlank()) "UUID not found in library!" else "Book not found in library!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (isUpdateComments && includeComments) {
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text("Comments (HTML supported)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = { Text("Book description or notes...") },
                )
            }

            if (isUpdateComments) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Programming, Python, Reference") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = uuid,
                onValueChange = { uuid = it },
                label = { Text(if (isReplaceCover) "Book UUID (Required)" else "Book UUID (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Directly target an existing book") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = if (isReplaceCover && isUuidMatched) matchedBookTitle ?: "" else title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !titleAuthorLocked,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = {
                    if (!titleAuthorLocked) {
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
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { val tmp = title; title = author; author = tmp },
                    enabled = !titleAuthorLocked
                ) {
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
                    value = if (isReplaceCover && isUuidMatched) matchedBookAuthor ?: "" else author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    placeholder = { Text("Unknown") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !titleAuthorLocked,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val canSend = title.isNotBlank() &&
                            (!isReplaceCover && !isUpdateComments || matchedBookId != null) &&
                            (uuid.isBlank() || isUuidMatched) &&
                            (!requiresUuidMatch || isUuidMatched)
                        if (canSend) {
                            onConfirm(
                                if (isReplaceCover) matchedBookTitle ?: title.trim() else title.trim(),
                                if (isReplaceCover) matchedBookAuthor ?: author.trim().ifBlank { "Unknown" } else author.trim().ifBlank { "Unknown" },
                                if (isArchive) archiveMode else null,
                                assembleBook,
                                isAltVersion,
                                matchedBookId,
                                uuid.trim().ifBlank { null },
                                if (isUpdateComments && includeComments) comments.trim() else null,
                                if (isUpdateComments) tags.trim().ifBlank { null } else null,
                            )
                        }
                    }),
                )
                if (!titleAuthorLocked && author.count { it == ',' } == 1) {
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

            if (!forceAssemble && !isReplaceCover && !isUpdateComments) {
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

            if (isArchive && !isReplaceCover && !isUpdateComments) {
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
                onClick = { 
                    onConfirm(
                        if (isReplaceCover) matchedBookTitle ?: title.trim() else title.trim(), 
                        if (isReplaceCover) matchedBookAuthor ?: author.trim().ifBlank { "Unknown" } else author.trim().ifBlank { "Unknown" }, 
                        if (isArchive) archiveMode else null, 
                        assembleBook, 
                        isAltVersion, 
                        matchedBookId, 
                        uuid.trim().ifBlank { null },
                        if (isUpdateComments && includeComments) comments.trim() else null,
                        if (isUpdateComments) tags.trim().ifBlank { null } else null,
                    ) 
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() &&
                    (!isReplaceCover && !isUpdateComments || matchedBookId != null) &&
                    (uuid.isBlank() || isUuidMatched) &&
                    (!requiresUuidMatch || isUuidMatched),
            ) {
                Text(
                    when {
                        isReplaceCover -> "Replace Cover"
                        isUpdateComments && includeComments -> "Update Comments"
                        isUpdateComments -> "Update Tags"
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

private fun mergeTags(existingTags: String, autoAddTags: String?): String {
    val combinedTags = listOf(existingTags, autoAddTags.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(", ")

    return combinedTags
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedWith { first, second -> first.compareTo(second, ignoreCase = true) }
        .joinToString(", ")
}
