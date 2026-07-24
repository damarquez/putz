package com.damarquez.putz.ui.transfers

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.damarquez.putz.data.repository.PendingConvertFormatRepository
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.data.repository.PendingDeletionActionRepository
import com.damarquez.putz.data.repository.PendingExtractOrRandomCoverRepository
import com.damarquez.putz.data.repository.PendingGenerateCoverRepository
import com.damarquez.putz.data.repository.PendingProtectBook
import com.damarquez.putz.data.repository.PendingProtectBookRepository
import com.damarquez.putz.data.repository.PendingSetPageCountRepository
import com.damarquez.putz.data.repository.PendingUnprotectBook
import com.damarquez.putz.data.repository.PendingUnprotectBookRepository
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.ui.files.CalibreConfirmationSheet
import com.damarquez.putz.ui.files.TransferRef
import com.damarquez.putz.ui.GlobalSyncViewModel
import com.damarquez.putz.ui.components.SyncProgressBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreTransfersScreen(  // CONTRACT: edit_metadata deep link
    onNavigateUp: () -> Unit,
    onOpenChain: () -> Unit,
    viewModel: CalibreTransfersViewModel,
    pendingCoverRepository: PendingCoverRepository,
    pendingCommentsRepository: PendingCommentsRepository,
    pendingGenerateCoverRepository: PendingGenerateCoverRepository,
    pendingExtractOrRandomCoverRepository: PendingExtractOrRandomCoverRepository,
    pendingSetPageCountRepository: PendingSetPageCountRepository,
    pendingConvertFormatRepository: PendingConvertFormatRepository,
    pendingDeletionActionRepository: PendingDeletionActionRepository,
    pendingEditMetadataRepository: com.damarquez.putz.data.repository.PendingEditMetadataRepository,
    pendingProtectBookRepository: PendingProtectBookRepository,
    pendingUnprotectBookRepository: PendingUnprotectBookRepository,
    syncViewModel: GlobalSyncViewModel,
) {
    val libraryHasUpdates by syncViewModel.libraryHasUpdates.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val transfers by viewModel.transfers.collectAsState()
    val filteredTransfers by viewModel.filteredTransfers.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) runCatching { searchFocusRequester.requestFocus() }
        else viewModel.setSearchQuery("")
    }
    val completedTransferRefs = remember(transfers) {
        transfers
            .filter { it.status == CalibreTransferStatus.COMPLETED && it.calibreBookUuid != null }
            .map { TransferRef(it.calibreBookUuid!!, it.title, it.author) }
            .distinctBy { it.uuid }
    }
    val chainedCount by viewModel.chainedCount.collectAsState()
    val daemonStatus by viewModel.daemonStatus.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val pendingAssemblyAppends by viewModel.pendingAssemblyAppends.collectAsState()
    val googleAccount by syncViewModel.googleAccount.collectAsState()
    val isGoogleSignedIn = googleAccount.isNotBlank()
    val isSyncing by viewModel.isSyncing.collectAsState(initial = false)
    val syncProgress by viewModel.syncProgress.collectAsState()
    val pendingDriveSyncConfirmation by viewModel.pendingDriveSyncConfirmation.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingProtectConfirmation by remember { mutableStateOf<PendingProtectBook?>(null) }
    var pendingUnprotectConfirmation by remember { mutableStateOf<PendingUnprotectBook?>(null) }
    var transferToDelete by remember { mutableStateOf<CalibreTransferEntity?>(null) }
    var transferToBrowse by remember { mutableStateOf<CalibreTransferEntity?>(null) }
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

    var assemblyCoverTarget by remember { mutableStateOf<CalibreTransferEntity?>(null) }
    var assemblyCoverPreviewUri by remember { mutableStateOf<Uri?>(null) }
    val cacheAssemblyCoverImage: (Uri, CalibreTransferEntity) -> Unit = { uri: Uri, target: CalibreTransferEntity ->
        scope.launch {
            val cachedFile = withContext(Dispatchers.IO) {
                try {
                    val previewsDir = File(context.cacheDir, "previews")
                    if (!previewsDir.exists()) previewsDir.mkdirs()
                    previewsDir.listFiles { f -> f.name.startsWith("assembly_cover_") }
                        ?.forEach { it.delete() }
                    val tempFile = File(previewsDir, "assembly_cover_${System.currentTimeMillis()}.jpg")
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
                assemblyCoverPreviewUri = FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", cachedFile)
                assemblyCoverTarget = target
            } else {
                snackbarHostState.showSnackbar("Clipboard image is empty or unavailable")
            }
        }
    }

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
        pendingExtractOrRandomCoverRepository.flow.collect { pending ->
            if (pending != null) {
                pendingExtractOrRandomCoverRepository.clear()
                viewModel.extractOrRandomCover(pending.uuid, pending.title, pending.author)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingConvertFormatRepository.flow.collect { pending ->
            if (pending != null) {
                pendingConvertFormatRepository.clear()
                viewModel.convertFormat(pending.uuid, pending.sourceFormat, pending.targetFormat, pending.title, pending.author)
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingProtectBookRepository.flow.collect { pending ->
            if (pending != null) {
                pendingProtectBookRepository.clear()
                pendingProtectConfirmation = pending
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingUnprotectBookRepository.flow.collect { pending ->
            if (pending != null) {
                pendingUnprotectBookRepository.clear()
                pendingUnprotectConfirmation = pending
            }
        }
    }

    LaunchedEffect(Unit) {
        pendingSetPageCountRepository.flow.collect { pending ->
            if (pending != null) {
                pendingSetPageCountRepository.clear()
                viewModel.setPageCount(pending.uuid, pending.pageCount, pending.title, pending.author)
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
            onConfirm = { title, author, _, _, _, matchedId, uuid, _, _, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCoverFromClipboard(clipboardImageUri!!, title, author, matchedId ?: 0L, uuid)
                }
                clipboardImageUri = null
                prefilledUuid = null
            },
            onAddToChain = { title, author, _, _, _, matchedId, uuid, _, _, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCoverFromClipboard(clipboardImageUri!!, title, author, matchedId ?: 0L, uuid, addToChain = true)
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

    if (assemblyCoverTarget != null && assemblyCoverPreviewUri != null) {
        AlertDialog(
            onDismissRequest = {
                assemblyCoverTarget = null
                assemblyCoverPreviewUri = null
            },
            title = { Text("Set cover for \"${assemblyCoverTarget!!.title}\"") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = assemblyCoverPreviewUri,
                        contentDescription = "Cover preview",
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Applied once this book finishes assembling and is added to Calibre.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setAssemblyCoverFromClipboard(assemblyCoverTarget!!.putioFileId, assemblyCoverPreviewUri!!)
                    assemblyCoverTarget = null
                    assemblyCoverPreviewUri = null
                }) {
                    Text("Set as cover")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    assemblyCoverTarget = null
                    assemblyCoverPreviewUri = null
                }) {
                    Text("Cancel")
                }
            },
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
            onConfirm = { title, author, _, _, _, matchedId, uuid, comments, tags, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCommentsFromClipboard(comments, tags, title, author, matchedId ?: 0L, uuid)
                }
                clipboardComments = null
                includeClipboardComments = true
                autoAddTags = null
                prefilledUuid = null
                pendingCommentsRepository.clear()
            },
            onAddToChain = { title, author, _, _, _, matchedId, uuid, comments, tags, _, _, _ ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCommentsFromClipboard(comments, tags, title, author, matchedId ?: 0L, uuid, addToChain = true)
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

    pendingProtectConfirmation?.let { protect ->
        AlertDialog(
            onDismissRequest = { pendingProtectConfirmation = null },
            title = { Text("Protect book") },
            text = {
                Text(
                    "Encrypt \"${protect.title}\" on disk? This cannot be undone without the protection key." +
                        if (protect.keepCover) " The existing cover will be kept." else " The cover will be replaced with a generated one.",
                )
            },
            confirmButton = {
                Row {
                    // CONTRACT: CHAIN
                    TextButton(onClick = {
                        viewModel.protectBook(protect.uuid, protect.title, protect.author, protect.keepCover, addToChain = true)
                        pendingProtectConfirmation = null
                    }) { Text("Add to chain") }
                    TextButton(onClick = {
                        viewModel.protectBook(protect.uuid, protect.title, protect.author, protect.keepCover)
                        pendingProtectConfirmation = null
                    }) { Text("Protect") }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProtectConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    pendingUnprotectConfirmation?.let { unprotect ->
        AlertDialog(
            onDismissRequest = { pendingUnprotectConfirmation = null },
            title = { Text("Unprotect book") },
            text = {
                Text(
                    "Decrypt \"${unprotect.title}\" and replace the encrypted files with plaintext versions?",
                )
            },
            confirmButton = {
                Row {
                    // CONTRACT: CHAIN
                    TextButton(onClick = {
                        viewModel.unprotectBook(unprotect.uuid, unprotect.title, unprotect.author, addToChain = true)
                        pendingUnprotectConfirmation = null
                    }) { Text("Add to chain") }
                    TextButton(onClick = {
                        viewModel.unprotectBook(unprotect.uuid, unprotect.title, unprotect.author)
                        pendingUnprotectConfirmation = null
                    }) { Text("Unprotect") }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnprotectConfirmation = null }) { Text("Cancel") }
            },
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
                Row {
                    // CONTRACT: CHAIN
                    TextButton(
                        onClick = {
                            val tags = batchTagInput.trim()
                            if (tags.isNotBlank()) {
                                viewModel.batchAddTags(uuids, tags, addToChain = true)
                            }
                            pendingBatchUuids = null
                            batchTagInput = ""
                        },
                        enabled = batchTagInput.isNotBlank(),
                    ) {
                        Text("Add to chain")
                    }
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
        val canCleanup = (isCompleted || isDuplicate) && transfer.hasPutioFile
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
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search transfers…") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { isSearchActive = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            } else {
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
                        // CONTRACT: CHAIN
                        IconButton(onClick = onOpenChain) {
                            BadgedBox(
                                badge = {
                                    if (chainedCount > 0) Badge { Text("$chainedCount") }
                                }
                            ) {
                                Icon(Icons.Default.Link, contentDescription = "Request chain")
                            }
                        }

                        if (transfers.isNotEmpty()) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search transfers")
                            }
                        }

                        if (greenTransfers.isNotEmpty()) {
                            IconButton(onClick = {
                                clearGreenAlsoDelete = false
                                showClearGreenDialog = true
                            }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear verified transfers")
                            }
                        }

                        IconButton(
                            onClick = { viewModel.requestSync() },
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
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SyncProgressBanner(
                progress = syncProgress,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            )
            if (transfers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Calibre transfers yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else if (searchQuery.isNotBlank() && filteredTransfers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transfers match \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                // distinctBy guards against a duplicate putioFileId from upstream crashing
                // LazyColumn with "Key already used" (putioFileId is used as the item key below).
                val dedupedTransfers = filteredTransfers.distinctBy { it.putioFileId }
                val activeTransfers = dedupedTransfers.filter { it.status != CalibreTransferStatus.COMPLETED }
                val completedTransfers = dedupedTransfers.filter { it.status == CalibreTransferStatus.COMPLETED }

                val listState = rememberLazyListState()

                // Index bookkeeping so the header/footer jump buttons land on the right row —
                // each section is [header item, N transfer items, footer item] back to back.
                val activeHeaderIndex = 0
                val activeFooterIndex = activeHeaderIndex + activeTransfers.size + 1
                val completedHeaderIndex =
                    (if (activeTransfers.isNotEmpty()) activeFooterIndex + 1 else 0)
                val completedFooterIndex = completedHeaderIndex + completedTransfers.size + 1

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (activeTransfers.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Active (${activeTransfers.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                )
                                IconButton(onClick = {
                                    scope.launch { listState.animateScrollToItem(activeFooterIndex) }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom of Active")
                                }
                            }
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
                                onCopyTitle = { title ->
                                    clipboardManager.setText(AnnotatedString(title))
                                    scope.launch { snackbarHostState.showSnackbar("Title copied") }
                                },
                                onCopyAuthor = { author ->
                                    clipboardManager.setText(AnnotatedString(author))
                                    scope.launch { snackbarHostState.showSnackbar("Author copied") }
                                },
                                uploadProgress = uploadProgress[transfer.putioFileId],
                                isPendingAppend = transfer.putioFileId in pendingAssemblyAppends,
                                onTap = { transferToBrowse = transfer },
                                onMakePriority = {
                                    scope.launch {
                                        val promoted = viewModel.makeTransferPriority(transfer.putioFileId)
                                        if (!promoted) {
                                            snackbarHostState.showSnackbar("Already being processed — too late to prioritize")
                                        }
                                    }
                                },
                                onRemoveFromChain = { viewModel.removeFromChain(transfer.putioFileId) },
                                onOpenChain = onOpenChain,
                                onSetCoverFromClipboard = {
                                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                                    val imgUri = clip?.getItemAt(0)?.uri
                                    val mime = imgUri?.let { context.contentResolver.getType(it) }
                                    if (imgUri != null && mime?.startsWith("image/") == true) {
                                        cacheAssemblyCoverImage(imgUri, transfer)
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("No image in clipboard") }
                                    }
                                },
                            )
                        }
                        item {
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                TextButton(onClick = {
                                    scope.launch { listState.animateScrollToItem(activeHeaderIndex) }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                                    Text("Back to top")
                                }
                            }
                        }
                    }

                    if (completedTransfers.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Completed (${completedTransfers.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                )
                                IconButton(onClick = {
                                    scope.launch { listState.animateScrollToItem(completedFooterIndex) }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom of Completed")
                                }
                            }
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
                                onCopyTitle = { title ->
                                    clipboardManager.setText(AnnotatedString(title))
                                    scope.launch { snackbarHostState.showSnackbar("Title copied") }
                                },
                                onCopyAuthor = { author ->
                                    clipboardManager.setText(AnnotatedString(author))
                                    scope.launch { snackbarHostState.showSnackbar("Author copied") }
                                },
                                onTap = { transferToBrowse = transfer },
                                onOpenChain = onOpenChain,
                            )
                        }
                        item {
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                TextButton(onClick = {
                                    scope.launch { listState.animateScrollToItem(completedHeaderIndex) }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                                    Text("Back to top")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    transferToBrowse?.let { transfer ->
        Dialog(
            onDismissRequest = { transferToBrowse = null },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
            ) {
                TransferBrowserSheet(
                    transfer = transfer,
                    onDismiss = { transferToBrowse = null },
                    onSave = if (transfer.status == CalibreTransferStatus.ASSEMBLED) {
                        { title, author, tags, items, ignoreCover ->
                            viewModel.updateAssemblyMetadata(transfer.putioFileId, title, author, tags, items, ignoreCover)
                            transferToBrowse = null
                        }
                    } else null,
                )
            }
        }
    }

    if (pendingDriveSyncConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDriveSyncConfirmation() },
            title = { Text("Sync via Google Drive?") },
            text = { Text("Tailscale LAN is not reachable. Are you sure you want to sync using Google Drive?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDriveSync() }) { Text("Sync") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDriveSyncConfirmation() }) { Text("Cancel") }
            },
        )
    }
}
