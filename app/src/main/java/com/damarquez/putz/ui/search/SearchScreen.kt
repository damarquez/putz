package com.damarquez.putz.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.damarquez.putz.data.model.MetaCandidate
import com.damarquez.putz.data.model.TorrentSearchResult
import com.damarquez.putz.ui.components.ErrorView
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val jackettConfigured by viewModel.jackettConfigured.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }

    // Hardware/gesture back inside the two-step Movies/TV flow returns to the candidate list
    BackHandler(enabled = uiState.selectedCandidate != null) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Content") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.mode == mode,
                        onClick = { viewModel.onModeChange(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                label = { Text(searchHint(uiState.mode)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        viewModel.search()
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    viewModel.search()
                }),
            )

            if (uiState.mode == SearchMode.TV) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.season,
                        onValueChange = { viewModel.onSeasonChange(it) },
                        label = { Text("Season") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = uiState.episode,
                        onValueChange = { viewModel.onEpisodeChange(it) },
                        label = { Text("Episode") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (uiState.mode == SearchMode.OTHER && !jackettConfigured) {
                Text(
                    text = "Jackett is not configured. Set its URL and API key in Settings to search general content.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            uiState.selectedCandidate?.let { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to results")
                    }
                    Text(
                        text = buildString {
                            append(candidate.name)
                            candidate.releaseInfo?.let { append(" ($it)") }
                            if (uiState.mode == SearchMode.TV) {
                                append("  S${uiState.season.ifBlank { "1" }}E${uiState.episode.ifBlank { "1" }}")
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when {
                uiState.isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    ErrorView(
                        message = uiState.error ?: "Unknown error",
                        onRetry = {
                            val selected = uiState.selectedCandidate
                            if (selected != null) viewModel.selectCandidate(selected)
                            else viewModel.search()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Candidate picking step for Movies / TV
                uiState.selectedCandidate == null && uiState.mode != SearchMode.OTHER && uiState.searched -> {
                    if (uiState.candidates.isEmpty()) {
                        EmptyState("No titles found for \"${uiState.query.trim()}\"")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(uiState.candidates, key = { it.imdbId }) { candidate ->
                                CandidateItem(
                                    candidate = candidate,
                                    onClick = { viewModel.selectCandidate(candidate) },
                                )
                            }
                        }
                    }
                }

                // Torrent results (Torrentio after candidate pick, or Jackett directly)
                uiState.searched -> {
                    if (uiState.results.isEmpty()) {
                        EmptyState("No torrents found")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(uiState.results, key = { it.magnet }) { result ->
                                TorrentResultItem(
                                    result = result,
                                    isAdding = result.magnet in uiState.addingMagnets,
                                    isAdded = result.magnet in uiState.addedMagnets,
                                    onAdd = { viewModel.addToPutio(result) },
                                )
                            }
                        }
                    }
                }

                else -> {
                    EmptyState(
                        message = "Search for content and add it to put.io as a transfer",
                        showIcon = true,
                    )
                }
            }
        }
    }
}

private fun searchHint(mode: SearchMode): String = when (mode) {
    SearchMode.MOVIES -> "Movie title"
    SearchMode.TV -> "TV show title"
    SearchMode.OTHER -> "Search ebooks, games, software…"
}

@Composable
private fun CandidateItem(
    candidate: MetaCandidate,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(candidate.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = candidate.releaseInfo?.let { info ->
            { Text(info, style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            AsyncImage(
                model = candidate.poster,
                contentDescription = null,
                modifier = Modifier
                    .width(40.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TorrentResultItem(
    result: TorrentSearchResult,
    isAdding: Boolean,
    isAdded: Boolean,
    onAdd: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val parts = buildList {
                result.sizeBytes?.let { add(formatSize(it)) }
                result.seeders?.let { add("$it seeders") }
                add(result.source)
            }
            Text(
                text = parts.joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            when {
                isAdding -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                isAdded -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Added",
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Default.AddCircleOutline,
                        contentDescription = "Add to put.io",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

@Composable
private fun EmptyState(message: String, showIcon: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showIcon) {
                Icon(
                    imageVector = Icons.Default.TravelExplore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb * kb -> String.format(Locale.US, "%.2f TB", bytes / (kb * kb * kb * kb))
        bytes >= kb * kb * kb -> String.format(Locale.US, "%.2f GB", bytes / (kb * kb * kb))
        bytes >= kb * kb -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
