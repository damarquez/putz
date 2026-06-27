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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.ui.window.DialogProperties
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import com.damarquez.putz.data.repository.CalibreBookMatch
import com.damarquez.putz.ui.components.CompactOutlinedTextField
import com.damarquez.putz.util.MetadataUtils

data class TransferRef(val uuid: String, val title: String, val author: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreConfirmationSheet(
    displayName: String,
    initialTitle: String,
    initialAuthor: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, archiveMode: String?, assembleBook: Boolean, isAltVersion: Boolean, calibreBookId: Long?, calibreBookUuid: String?, comments: String?, tags: String?, isProtected: Boolean) -> Unit,
    checkExists: suspend (String, String) -> Long?,
    checkExistsByUuid: suspend (String) -> CalibreBookMatch?,
    isArchive: Boolean = false,
    forceAssemble: Boolean = false,
    isReplaceCover: Boolean = false,
    isUpdateComments: Boolean = false,
    initialUuid: String = "",
    transferRefs: List<TransferRef> = emptyList(),
    initialComments: String = "",
    initialTags: String = "",
    autoAddTags: String? = null,
    includeComments: Boolean = true,
    coverImageUri: Uri? = null,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    val authorHasAnd = author.contains(Regex("""\band\b""", RegexOption.IGNORE_CASE))
    var uuid by remember { mutableStateOf(initialUuid) }
    var comments by remember { mutableStateOf(initialComments) }
    var tags by remember { mutableStateOf(initialTags) }
    var additionalTags by remember { mutableStateOf("") }

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
    var uuidFromTransfer by remember { mutableStateOf(false) }
    var selectedTransferRef by remember { mutableStateOf<TransferRef?>(null) }
    var showTransferPicker by remember { mutableStateOf(false) }

    var archiveMode by remember { mutableStateOf("default") }
    var assembleBook by remember { mutableStateOf(forceAssemble) }
    var isAltVersion by remember { mutableStateOf(false) }
    var isProtected by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val titleAuthorLocked = isReplaceCover
    val requiresUuidMatch = isReplaceCover

    LaunchedEffect(uuid, autoAddTags) {
        if (uuidFromTransfer) return@LaunchedEffect
        if (uuid.isNotBlank()) {
            isUuidValidating = true
            val match = checkExistsByUuid(uuid.trim())
            if (match != null) {
                matchedBookId = match.id
                matchedBookTitle = match.title
                matchedBookAuthor = match.author
                isUuidMatched = true
                title = match.title
                author = match.author
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

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        confirmButton = { },
        title = {
            Text(
                text = when {
                    isReplaceCover -> "Replace Book Cover"
                    isUpdateComments && includeComments -> "Update Book Comments"
                    isUpdateComments -> "Update Book Tags"
                    else -> "Send to Calibre"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                if (coverImageUri != null) {
                    AsyncImage(
                        model = coverImageUri,
                        contentDescription = "Cover preview",
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            val query = MetadataUtils.webSearchQuery(displayName)
                            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                                putExtra(SearchManager.QUERY, query)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                    )
                }

                if (matchedBookId != null) {
                    Spacer(Modifier.height(8.dp))
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
                } else if (uuidFromTransfer && selectedTransferRef != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "From completed transfer: ${selectedTransferRef!!.title} by ${selectedTransferRef!!.author}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (isReplaceCover || isUpdateComments || (uuid.isNotBlank() && !isUuidValidating)) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (uuid.isNotBlank()) "UUID not found in library (needs sync?)!" else "Book not found in library (needs sync?)!",
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
                    CompactOutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = "Tags",
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Programming, Python, Reference",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactOutlinedTextField(
                        value = uuid,
                        onValueChange = { uuid = it },
                        label = if (isReplaceCover) "Book UUID (Required)" else "Book UUID (Optional)",
                        modifier = Modifier.weight(1f),
                        enabled = !uuidFromTransfer,
                        placeholder = "Directly target an existing book",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    if (transferRefs.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { showTransferPicker = true }) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Pick from completed transfers",
                                tint = if (uuidFromTransfer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (uuidFromTransfer) {
                    TextButton(
                        onClick = {
                            uuidFromTransfer = false
                            selectedTransferRef = null
                            isUuidMatched = false
                            uuid = ""
                        },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear selection", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (showTransferPicker) {
                    AlertDialog(
                        onDismissRequest = { showTransferPicker = false },
                        title = { Text("Pick completed transfer") },
                        text = {
                            LazyColumn {
                                items(transferRefs) { ref ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                uuid = ref.uuid
                                                title = ref.title
                                                author = ref.author
                                                uuidFromTransfer = true
                                                isUuidMatched = true
                                                selectedTransferRef = ref
                                                matchedBookId = null
                                                matchedBookTitle = null
                                                matchedBookAuthor = null
                                                showTransferPicker = false
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp)
                                    ) {
                                        Text(ref.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(ref.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showTransferPicker = false }) { Text("Cancel") }
                        },
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactOutlinedTextField(
                        value = if (isReplaceCover && isUuidMatched) matchedBookTitle ?: "" else title,
                        onValueChange = { title = it },
                        label = "Title",
                        modifier = Modifier.weight(1f),
                        enabled = !titleAuthorLocked,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        trailingIcon = if (!titleAuthorLocked) {
                            {
                                IconButton(onClick = { title = displayName.substringBeforeLast('.') }) {
                                    Icon(
                                        imageVector = Icons.Default.FileOpen,
                                        contentDescription = "Load filename as title",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        } else null,
                    )
                    if (!titleAuthorLocked && title.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                title = title
                                    .replace('_', ' ')
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                                    .split(" ")
                                    .joinToString(" ") { word ->
                                        word.lowercase().replaceFirstChar { it.uppercase() }
                                    }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = "Fix title capitalisation",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        modifier = Modifier.size(28.dp),
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
                    CompactOutlinedTextField(
                        value = if (isReplaceCover && isUuidMatched) matchedBookAuthor ?: "" else author,
                        onValueChange = { author = it },
                        label = "Author",
                        placeholder = "Unknown",
                        modifier = Modifier.weight(1f),
                        enabled = !titleAuthorLocked,
                        isError = authorHasAnd,
                        supportingText = if (authorHasAnd) {
                            { Text("Use commas for multiple authors — \"and\" suggests title/author may be swapped") }
                        } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val canSend = title.isNotBlank() &&
                                !authorHasAnd &&
                                (!isReplaceCover && !isUpdateComments || matchedBookId != null || uuidFromTransfer) &&
                                (uuid.isBlank() || isUuidMatched || uuidFromTransfer) &&
                                (!requiresUuidMatch || isUuidMatched || uuidFromTransfer)
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
                                    if (isUpdateComments) tags.trim().ifBlank { null } else additionalTags.trim().ifBlank { null },
                                    isProtected,
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
                                    .split(" ")
                                    .joinToString(" ") { word ->
                                        word.lowercase().replaceFirstChar { it.uppercaseChar() }
                                    }
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

                if (!isReplaceCover && !isUpdateComments) {
                    Spacer(Modifier.height(8.dp))
                    CompactOutlinedTextField(
                        value = additionalTags,
                        onValueChange = { additionalTags = it },
                        label = "Tags (optional)",
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Programming, Python, Reference",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
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

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Encrypt format",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Daemon encrypts file before adding to Calibre",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = isProtected,
                            onCheckedChange = { isProtected = it }
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
                            if (isUpdateComments) tags.trim().ifBlank { null } else additionalTags.trim().ifBlank { null },
                            isProtected,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = title.isNotBlank() &&
                        !authorHasAnd &&
                        (!isReplaceCover && !isUpdateComments || matchedBookId != null || uuidFromTransfer) &&
                        (uuid.isBlank() || isUuidMatched || uuidFromTransfer) &&
                        (!requiresUuidMatch || isUuidMatched || uuidFromTransfer),
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
    )
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
