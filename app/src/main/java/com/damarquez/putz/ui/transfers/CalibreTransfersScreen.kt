package com.damarquez.putz.ui.transfers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.local.CalibreTransferStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreTransfersScreen(
    onNavigateUp: () -> Unit,
    viewModel: CalibreTransfersViewModel,
) {
    val transfers by viewModel.transfers.collectAsState()
    val daemonStatus by viewModel.daemonStatus.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState(initial = false)

    var transferToDelete by remember { mutableStateOf<CalibreTransferEntity?>(null) }
    var alsoDeleteFromPutio by remember { mutableStateOf(false) }

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
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync metadata.db",
                            modifier = if (isSyncing) Modifier.rotate(rotation) else Modifier
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (transfers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
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
        } else {
            val activeTransfers = transfers.filter { it.status != CalibreTransferStatus.COMPLETED }
            val completedTransfers = transfers.filter { it.status == CalibreTransferStatus.COMPLETED }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
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
