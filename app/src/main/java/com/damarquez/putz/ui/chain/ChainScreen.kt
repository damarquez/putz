package com.damarquez.putz.ui.chain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.ui.files.ReorderArrowButton

// CONTRACT: CHAIN
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainScreen(
    onNavigateUp: () -> Unit,
    viewModel: ChainViewModel,
) {
    val chainedTransfers by viewModel.chainedTransfers.collectAsState()
    val isPlacing by viewModel.isPlacing.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Local, reorderable copy — mirrors the exact pattern MergePackSheet.kt uses for its own
    // reorder list. Persisted back to chainPosition via viewModel.reorder() on every move so a
    // process death mid-reorder doesn't lose it (unlike MergePackSheet's transient in-sheet list,
    // which only needs to survive until "Continue" is tapped).
    var ordered by remember(chainedTransfers) { mutableStateOf(chainedTransfers) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Request Chain") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (ordered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No requests chained yet.\nLong-press \"Add to chain\" on a confirmation " +
                            "sheet to stage a request here instead of sending it immediately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(ordered, key = { _, t -> t.putioFileId }) { index, transfer ->
                        ChainRow(
                            transfer = transfer,
                            canMoveUp = index > 0,
                            canMoveDown = index < ordered.lastIndex,
                            onMoveUp = {
                                if (index > 0) {
                                    ordered = ordered.toMutableList().also {
                                        val tmp = it[index]; it[index] = it[index - 1]; it[index - 1] = tmp
                                    }
                                    viewModel.reorder(ordered.map { it.putioFileId })
                                }
                            },
                            onMoveToTop = {
                                if (index > 0) {
                                    ordered = ordered.toMutableList().also { it.add(0, it.removeAt(index)) }
                                    viewModel.reorder(ordered.map { it.putioFileId })
                                }
                            },
                            onMoveDown = {
                                if (index < ordered.lastIndex) {
                                    ordered = ordered.toMutableList().also {
                                        val tmp = it[index]; it[index] = it[index + 1]; it[index + 1] = tmp
                                    }
                                    viewModel.reorder(ordered.map { it.putioFileId })
                                }
                            },
                            onMoveToBottom = {
                                if (index < ordered.lastIndex) {
                                    ordered = ordered.toMutableList().also { it.add(it.removeAt(index)) }
                                    viewModel.reorder(ordered.map { it.putioFileId })
                                }
                            },
                            onRemove = { viewModel.removeFromChain(transfer.putioFileId) },
                        )
                    }
                }
            }

            Button(
                onClick = { viewModel.placeChain(onPlaced = onNavigateUp) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = ordered.isNotEmpty() && !isPlacing,
            ) {
                if (isPlacing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (isPlacing) "Placing chain…" else "Place chain (${ordered.size})")
            }
        }
    }
}

@Composable
private fun ChainRow(
    transfer: CalibreTransferEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToBottom: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = transfer.title, style = MaterialTheme.typography.titleMedium)
            if (transfer.author.isNotBlank()) {
                Text(
                    text = transfer.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ReorderArrowButton(
            icon = Icons.Default.ArrowUpward,
            contentDescription = "Move up",
            enabled = canMoveUp,
            onClick = onMoveUp,
            onLongClick = onMoveToTop,
        )
        ReorderArrowButton(
            icon = Icons.Default.ArrowDownward,
            contentDescription = "Move down",
            enabled = canMoveDown,
            onClick = onMoveDown,
            onLongClick = onMoveToBottom,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove from chain (send now)")
        }
    }
}
