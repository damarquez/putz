package com.damarquez.putz.ui.transfers

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.damarquez.putz.data.repository.PendingCommentsRepository
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.data.repository.PendingDeletionActionRepository
import com.damarquez.putz.data.repository.PendingGenerateCoverRepository
import com.damarquez.putz.data.repository.PendingSetPageCountRepository
import com.damarquez.putz.util.MetadataUtils
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.ui.files.CalibreConfirmationSheet
import com.damarquez.putz.ui.files.TransferRef
import com.damarquez.putz.ui.GlobalSyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreTransfersScreen(  // CONTRACT: edit_metadata deep link
    onNavigateUp: () -> Unit,
    viewModel: CalibreTransfersViewModel,
    pendingCoverRepository: PendingCoverRepository,
    pendingCommentsRepository: PendingCommentsRepository,
    pendingGenerateCoverRepository: PendingGenerateCoverRepository,
    pendingSetPageCountRepository: PendingSetPageCountRepository,
    pendingDeletionActionRepository: PendingDeletionActionRepository,
    pendingEditMetadataRepository: com.damarquez.putz.data.repository.PendingEditMetadataRepository,
) {
    val syncViewModel: GlobalSyncViewModel = hiltViewModel()
    val libraryHasUpdates by syncViewModel.libraryHasUpdates.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val transfers by viewModel.transfers.collectAsState()
    val completedTransferRefs = remember(transfers) {
        transfers
            .filter { it.status == CalibreTransferStatus.COMPLETED && it.calibreBookUuid != null }
            .map { TransferRef(it.calibreBookUuid!!, it.title, it.author) }
            .distinctBy { it.uuid }
    }
    val daemonStatus by viewModel.daemonStatus.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val pendingAssemblyAppends by viewModel.pendingAssemblyAppends.collectAsState()
    val googleAccount by syncViewModel.googleAccount.collectAsState()
    val isGoogleSignedIn = googleAccount.isNotBlank()
    val isSyncing by viewModel.isSyncing.collectAsState(initial = false)
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var transferToDelete by remember { mutableStateOf<CalibreTransferEntity?>(null) }
    var alsoDeleteFromPutio by remember { mutableStateOf(false) }
    var showClearGreenDialog by remember { mutableStateOf(false) }
    var clearGreenAlsoDelete by remember { mutableStateOf(false) }
    val greenTransfers = remember(transfers) {
        transfers.filter { it.status == CalibreTransferStatus.COMPLETED && it.libraryVerified }
    }
    
    var clipboardImageUri by remember { mutableStateOf<Uri?>(null) }
    var clipboardComments by remember { mutableStateOf<String?>(null) }
    var includeClipboardComments by remember { mutableStateOf(true) }
    var autoAddTags by remember { mutableStateOf<String?>(null) }
    var prefilledUuid by remember { mutableStateOf<String?>(null) }
    var pendingBatchUuids by remember { mutableStateOf<List<String>?>(null) }
    var batchTagInput by remember { mutableStateOf("") }

    val cacheClipboardImage: (Uri, String?) -> Unit = { uri: Uri, uuid: String? ->
        scope.launch {
            val cachedFile = withContext(Dispatchers.IO) {
                try {
                    val previewsDir = File(context.cacheDir, "previews")
                    if (!previewsDir.exists()) previewsDir.mkdirs()

                    // Delete any previous clipboard cover files so Coil's cache key changes each
                    // time — a fixed name would cause Coil to serve the stale cached image.
                    previewsDir.listFiles { f -> f.name.startsWith("clipboard_cover_") }
                        ?.forEach { it.delete() }

                    val tempFile = File(previewsDir, "clipboard_cover_${System.currentTimeMillis()}.jpg")
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream == null) return@withContext null
                    stream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.length() == 0L) null else tempFile
                } catch (e: Exception) {
                    null
                }
            }
            if (cachedFile != null) {
                clipboardImageUri = FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", cachedFile)
                prefilledUuid = uuid
            } else {
                snackbarHostState.showSnackbar("Clipboard image is empty or unavailable")
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingCoverRepository.flow.collect { pending ->
            if (pending != null) {
                pendingCoverRepository.clear()
                cacheClipboardImage(pending.imageUri, pending.uuid)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingGenerateCoverRepository.flow.collect { pending ->
            if (pending != null) {
                pendingGenerateCoverRepository.clear()
                viewModel.generateCover(pending.uuid, pending.title, pending.author)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingSetPageCountRepository.flow.collect { pending ->
            if (pending != null) {
                pendingSetPageCountRepository.clear()
                viewModel.setPageCount(pending.uuid, pending.pageCount)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingEditMetadataRepository.flow.collect { pending ->
            if (pending != null) {
                pendingEditMetadataRepository.clear()
                viewModel.sendEditMetadata(pending)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingDeletionActionRepository.flow.collect { pending ->
            if (pending != null) {
                pendingDeletionActionRepository.clear()
                viewModel.handleDeletionAction(pending)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingCommentsRepository.flow.collect { pending ->
            if (pending != null) {
                pendingCommentsRepository.clear()
                clipboardComments = pending.text ?: ""
                includeClipboardComments = pending.includeComments
                autoAddTags = pending.autoAddTags
                prefilledUuid = pending.uuid
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingCommentsRepository.batchTagsFlow.collect { pending ->
            if (pending != null) {
                pendingCommentsRepository.clearBatchTags()
                batchTagInput = ""
                pendingBatchUuids = pending.uuids
            }
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sync")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable<Float>(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    if (clipboardImageUri != null) {
        CalibreConfirmationSheet(
            displayName = "Clipboard Image",
            initialTitle = "",
            initialAuthor = "",
            onDismiss = {
                clipboardImageUri = null
                prefilledUuid = null
            },
            onConfirm = { title, author, _, _, _, matchedId, uuid, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCoverFromClipboard(clipboardImageUri!!, title, author, matchedId ?: 0L, uuid)
                }
                clipboardImageUri = null
                prefilledUuid = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isReplaceCover = true,
            initialUuid = prefilledUuid ?: "",
            transferRefs = completedTransferRefs,
            coverImageUri = clipboardImageUri,
        )
    }

    if (clipboardComments != null) {
        CalibreConfirmationSheet(
            displayName = "Clipboard Text",
            initialTitle = "",
            initialAuthor = "",
            onDismiss = {
                clipboardComments = null
                includeClipboardComments = true
                autoAddTags = null
                prefilledUuid = null
                pendingCommentsRepository.clear()
            },
            onConfirm = { title, author, _, _, _, matchedId, uuid, comments, tags ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCommentsFromClipboard(comments, tags, title, author, matchedId ?: 0L, uuid)
                }
                clipboardComments = null
                includeClipboardComments = true
                autoAddTags = null
                prefilledUuid = null
                pendingCommentsRepository.clear()
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isUpdateComments = true,
            initialUuid = prefilledUuid ?: "",
            initialComments = clipboardComments ?: "",
            autoAddTags = autoAddTags,
            includeComments = includeClipboardComments,
            transferRefs = completedTransferRefs,
        )
    }

    pendingBatchUuids?.let { uuids ->
        AlertDialog(
            onDismissRequest = { pendingBatchUuids = null; batchTagInput = "" },
            title = { Text("Add tag to ${uuids.size} book${if (uuids.size == 1) "" else "s"}") },
            text = {
                OutlinedTextField(
                    value = batchTagInput,
                    onValueChange = { batchTagInput = it },
                    label = { Text("New tag(s)") },
                    placeholder = { Text("e.g. read, fiction") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tags = batchTagInput.trim()
                        if (tags.isNotBlank()) {
                            viewModel.batchAddTags(uuids, tags)
                        }
                        pendingBatchUuids = null
                        batchTagInput = ""
                    },
                    enabled = batchTagInput.isNotBlank(),
                ) {
                    Text("Add tag")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchUuids = null; batchTagInput = "" }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showClearGreenDialog) {
        AlertDialog(
            onDismissRequest = { showClearGreenDialog = false },
            title = { Text("Clear ${greenTransfers.size} verified transfer${if (greenTransfers.size == 1) "" else "s"}?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clearGreenAlsoDelete,
                        onCheckedChange = { clearGreenAlsoDelete = it },
                    )
                    Text("Also delete files from put.io")
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearGreenTransfers(clearGreenAlsoDelete)
                    showClearGreenDialog = false
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearGreenDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    transferToDelete?.let { transfer ->
        val isCompleted = transfer.status == CalibreTransferStatus.COMPLETED
        val isDuplicate = transfer.status == CalibreTransferStatus.FAILED && 
                         transfer.errorMessage?.contains("already has format", ignoreCase = true) == true
        val canCleanup = isCompleted || isDuplicate
        val isLocal = transfer.isTempUpload
        
        AlertDialog(
            onDismissRequest = { transferToDelete = null },
            title = { Text("Remove transfer?") },
            text = {
                if (canCleanup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alsoDeleteFromPutio,
                            onCheckedChange = { alsoDeleteFromPutio = it },
                        )
                        Text(if (isLocal) "Also detach file from Putz" else "Also delete file from put.io")
                    }
                } else {
                    Text("Remove this transfer from the list?")
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (canCleanup && alsoDeleteFromPutio) {
                        viewModel.deleteOrDetach(transfer)
                    } else {
                        viewModel.removeTransfer(transfer.putioFileId)
                    }
                    transferToDelete = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { transferToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Calibre Transfers")
                        daemonStatus?.let { status ->
                            val isIdle = status.equals("IDLE", ignoreCase = true)
                            Text(
                                text = "Daemon: ${if (isIdle) "Idle" else "Running"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isIdle)
                                    MaterialTheme.colorScheme.outline 
                                else 
                                    com.damarquez.putz.ui.theme.SuccessGreen
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (greenTransfers.isNotEmpty()) {
                        IconButton(onClick = {
                            clearGreenAlsoDelete = false
                            showClearGreenDialog = true
                        }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear verified transfers")
                        }
                    }

                    IconButton(
                        onClick = {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val primaryClip = clipboardManager.primaryClip
                            if (primaryClip != null && primaryClip.itemCount > 0) {
                                val item = primaryClip.getItemAt(0)
                                val uri = item.uri
                                val text = item.text?.toString()
                                val htmlText = item.htmlText

                                if (uri != null) {
                                    val type = context.contentResolver.getType(uri)
                                    if (type?.startsWith("image/") == true) {
                                        cacheClipboardImage(uri, null)
                                    } else if (!text.isNullOrBlank() || !htmlText.isNullOrBlank()) {
                                        clipboardComments = MetadataUtils.sanitizeHtml(text ?: "", htmlText)
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("Clipboard contains a URI but it's not an image or text") }
                                    }
                                } else if (!text.isNullOrBlank() || !htmlText.isNullOrBlank()) {
                                    clipboardComments = MetadataUtils.sanitizeHtml(text ?: "", htmlText)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("Clipboard content not supported (need image or text)") }
                                }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
                            }
                        },
                        enabled = isGoogleSignedIn
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
                    }

                    IconButton(
                        onClick = { viewModel.syncMetadata() },
                        enabled = !isSyncing && isGoogleSignedIn
                    ) {
                        BadgedBox(
                            badge = {
                                if (libraryHasUpdates) {
                                    Badge()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Sync metadata.db",
                                modifier = if (isSyncing) Modifier.rotate(rotation) else Modifier
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val primaryClip = clipboardManager.primaryClip
                            if (primaryClip != null && primaryClip.itemCount > 0) {
                                val item = primaryClip.getItemAt(0)
                                val uri = item.uri
                                val text = item.text?.toString()
                                val htmlText = item.htmlText
                                if (uri != null) {
                                    val type = context.contentResolver.getType(uri)
                                    if (type?.startsWith("image/") == true) {
                                        cacheClipboardImage(uri, null)
                                    } else if (!text.isNullOrBlank() || !htmlText.isNullOrBlank()) {
                                        clipboardComments = MetadataUtils.sanitizeHtml(text ?: "", htmlText)
                                    }
                                } else if (!text.isNullOrBlank() || !htmlText.isNullOrBlank()) {
                                    clipboardComments = MetadataUtils.sanitizeHtml(text ?: "", htmlText)
                                }
                            }
                        }
                    )
                }
        ) {
            if (transfers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Calibre transfers yet\n(Use the paste button or long-press to update from clipboard)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                val activeTransfers = transfers.filter { it.status != CalibreTransferStatus.COMPLETED }
                val completedTransfers = transfers.filter { it.status == CalibreTransferStatus.COMPLETED }

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (activeTransfers.isNotEmpty()) {
                        item {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            items = activeTransfers,
                            key = { it.putioFileId }
                        ) { transfer ->
                            CalibreTransferItem(
                                transfer = transfer,
                                onDelete = {
                                    alsoDeleteFromPutio = true
                                    transferToDelete = transfer
                                },
                                onProbe = {
                                    viewModel.probeTransfer(transfer.putioFileId)
                                },
                                onRetry = {
                                    viewModel.retryTransfer(transfer.putioFileId)
                                },
                                onCopyJson = { json ->
                                    clipboardManager.setText(AnnotatedString(json))
                                    scope.launch { snackbarHostState.showSnackbar("JSON copied") }
                                },
                                uploadProgress = uploadProgress[transfer.putioFileId],
                                isPendingAppend = transfer.putioFileId in pendingAssemblyAppends,
                            )
                        }
                    }

                    if (completedTransfers.isNotEmpty()) {
                        item {
                            Text(
                                text = "Completed",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            items = completedTransfers,
                            key = { it.putioFileId }
                        ) { transfer ->
                            CalibreTransferItem(
                                transfer = transfer,
                                onDelete = {
                                    alsoDeleteFromPutio = true
                                    transferToDelete = transfer
                                },
                                onProbe = {
                                    viewModel.probeTransfer(transfer.putioFileId)
                                },
                                onRetry = {
                                    viewModel.retryTransfer(transfer.putioFileId)
                                },
                                onCopyJson = { json ->
                                    clipboardManager.setText(AnnotatedString(json))
                                    scope.launch { snackbarHostState.showSnackbar("JSON copied") }
                                },
                                uploadProgress = uploadProgress[transfer.putioFileId],
                                isPendingAppend = transfer.putioFileId in pendingAssemblyAppends,
                                onCopyUuid = { uuid ->
                                    clipboardManager.setText(AnnotatedString(uuid))
                                    scope.launch { snackbarHostState.showSnackbar("UUID copied") }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
