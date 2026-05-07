package com.damarquez.putz.ui.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreTransfersScreen(
    onNavigateUp: () -> Unit,
    viewModel: CalibreTransfersViewModel,
) {
    val transfers by viewModel.transfers.collectAsState()
    var fileToDeleteFromPutio by remember { mutableStateOf<Long?>(null) }

    if (fileToDeleteFromPutio != null) {
        AlertDialog(
            onDismissRequest = { fileToDeleteFromPutio = null },
            title = { Text("Delete from put.io?") },
            text = { Text("The book has been successfully added to Calibre. Do you want to remove the original file from your put.io account now?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFromPutio(fileToDeleteFromPutio!!)
                        fileToDeleteFromPutio = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete File")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDeleteFromPutio = null }) {
                    Text("Keep File")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibre Transfers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncMetadata() }) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync metadata.db")
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(
                    items = transfers,
                    key = { it.putioFileId }
                ) { transfer ->
                    CalibreTransferItem(
                        transfer = transfer,
                        onRemove = { viewModel.removeTransfer(transfer.putioFileId) },
                        onDeleteFromPutio = { fileToDeleteFromPutio = transfer.putioFileId }
                    )
                }
            }
        }
    }
}
