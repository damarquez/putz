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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
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
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus
import com.damarquez.putz.ui.files.CalibreConfirmationSheet
import com.damarquez.putz.ui.GlobalSyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreTransfersScreen(
    onNavigateUp: () -> Unit,
    viewModel: CalibreTransfersViewModel,
    pendingCoverRepository: com.damarquez.putz.data.repository.PendingCoverRepository,
) {
    val syncViewModel: GlobalSyncViewModel = hiltViewModel()
    val libraryHasUpdates by syncViewModel.libraryHasUpdates.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers by viewModel.transfers.collectAsState()
    val daemonStatus by viewModel.daemonStatus.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState(initial = false)
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var transferToDelete by remember { mutableStateOf<CalibreTransferEntity?>(null) }
    var alsoDeleteFromPutio by remember { mutableStateOf(false) }
    
    var clipboardImageUri by remember { mutableStateOf<Uri?>(null) }
    var prefilledUuid by remember { mutableStateOf<String?>(null) }
    val calibreSheetState = rememberModalBottomSheetState()

    val pendingCoverUuid by pendingCoverRepository.uuidFlow.collectAsState()

    val cacheClipboardImage: (Uri) -> Unit = { uri: Uri ->
        scope.launch {
            val cachedFile = withContext(Dispatchers.IO) {
                try {
                    val previewsDir = File(context.cacheDir, "previews")
                    if (!previewsDir.exists()) previewsDir.mkdirs()
                    
                    val tempFile = File(previewsDir, "clipboard_cover_temp.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                } catch (e: Exception) {
                    null
                }
            }
            if (cachedFile != null) {
                clipboardImageUri = FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", cachedFile)
            } else {
                snackbarHostState.showSnackbar("Failed to cache clipboard image")
            }
        }
    }

    LaunchedEffect(pendingCoverUuid) {
        pendingCoverUuid?.let { uuid: String ->
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val primaryClip = clipboardManager.primaryClip
            if (primaryClip != null && primaryClip.itemCount > 0) {
                val item = primaryClip.getItemAt(0)
                val uri = item.uri
                if (uri != null) {
                    val type = context.contentResolver.getType(uri)
                    if (type?.startsWith("image/") == true) {
                        prefilledUuid = uuid
                        cacheClipboardImage(uri)
                    } else {
                        snackbarHostState.showSnackbar("Clipboard does not contain an image")
                    }
                } else {
                    snackbarHostState.showSnackbar("Clipboard is empty or not an image")
                }
            } else {
                snackbarHostState.showSnackbar("Clipboard is empty")
            }
            pendingCoverRepository.clear()
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
            sheetState = calibreSheetState,
            onDismiss = { 
                clipboardImageUri = null
                prefilledUuid = null
            },
            onConfirm = { title, author, _, _, _, matchedId, uuid ->
                if (matchedId != null || uuid != null) {
                    viewModel.replaceCoverFromClipboard(clipboardImageUri!!, title, author, matchedId ?: 0L, uuid)
                }
                clipboardImageUri = null
                prefilledUuid = null
            },
            checkExists = { title, author -> viewModel.checkBookExists(title, author) },
            checkExistsByUuid = { uuid -> viewModel.checkBookExistsByUuid(uuid) },
            isReplaceCover = true,
            initialUuid = prefilledUuid ?: ""
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
                            Text(
                                text = "Daemon: $status",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status == "IDLE") 
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
                    IconButton(
                        onClick = { viewModel.syncMetadata() },
                        enabled = !isSyncing
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
                                if (uri != null) {
                                    val type = context.contentResolver.getType(uri)
                                    if (type?.startsWith("image/") == true) {
                                        cacheClipboardImage(uri)
                                    }
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
                        text = "No Calibre transfers yet\n(Long-press to paste cover from clipboard)",
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
                                }
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
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
