package com.damarquez.putz.ui.converter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.converter.ConverterItem
import com.damarquez.putz.data.converter.ConverterSearchResult
import com.damarquez.putz.data.converter.ConverterStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Monitor + control for the daemon PC's video converter (CONTRACTS.md §48). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoConversionsScreen(
    viewModel: VideoConversionsViewModel,
    onNavigateUp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.messages.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video conversions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val paused = state.status?.paused == true
                    if (state.status != null) {
                        IconButton(onClick = { viewModel.setPaused(!paused) }) {
                            Icon(
                                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (paused) "Resume" else "Pause",
                            )
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rescan Plex library") },
                            onClick = { menuOpen = false; viewModel.rescan() },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Row(Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                state.error != null && state.status == null -> Text(
                    "Can't reach the video converter: ${state.error}",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    state.status?.let { StatusCard(it, state.error) }
                    TabRow(selectedTabIndex = state.tab.ordinal) {
                        ConverterTab.entries.forEach { tab ->
                            Tab(
                                selected = state.tab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                text = {
                                    Text(if (tab == ConverterTab.QUEUE) "${tab.label} (${state.queueTotal})" else tab.label)
                                },
                            )
                        }
                    }
                    when (state.tab) {
                        ConverterTab.QUEUE -> QueueList(state.queue, viewModel)
                        ConverterTab.HISTORY -> HistoryList(state, viewModel)
                        ConverterTab.ADD -> AddTab(state, viewModel)
                    }
                }
            }
        }
    }
}

private val PHASE_LABELS = mapOf(
    "probing" to "Checking the file",
    "waiting_for_playback" to "Waiting — Plex is playing something",
    "copying_in" to "Copying from the NAS",
    "converting" to "Converting",
    "verifying" to "Verifying against the original",
    "copying_out" to "Copying back to the NAS",
    "replacing" to "Replacing the original",
    "plex_rescan" to "Waiting for Plex to pick it up",
    "keeping_for_review" to "Saving the converted file for review",
)

@Composable
private fun StatusCard(status: ConverterStatus, error: String?) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            val headline = when (status.state) {
                "converting" -> "Converting"
                "waiting_for_playback" -> "Waiting for playback to end"
                "paused" -> "Paused"
                "idle" -> if ((status.counts["queued"] ?: 0) == 0) "Idle — nothing to convert" else "Idle"
                "disabled" -> "Disabled in the daemon config"
                else -> status.state
            }
            Text(headline, style = MaterialTheme.typography.titleMedium)
            status.current?.let { cur ->
                Spacer(Modifier.height(6.dp))
                Text(cur.title ?: "", fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                val phase = PHASE_LABELS[cur.phase] ?: cur.phase ?: ""
                val extras = buildList {
                    cur.speed?.let { add(String.format(Locale.US, "%.1f× realtime", it)) }
                    cur.phaseEtaS?.let { add("~${formatDuration(it)} left") }
                }.joinToString(" · ")
                Text(if (extras.isBlank()) phase else "$phase · $extras",
                    style = MaterialTheme.typography.bodySmall)
                cur.startedAt?.let { started ->
                    val running = (System.currentTimeMillis() / 1000.0 - started).toLong()
                    Text("Started ${formatWhen(started)} · running for ${formatDuration(maxOf(0, running))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                cur.progress?.let {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
            val playing = status.sessions.filter { it.state == "playing" || it.state == "buffering" }
            if (playing.isNotEmpty()) {
                Text("▶ Playing: " + playing.joinToString { "${it.title} (${it.user ?: "?"})" },
                    style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            val c = status.counts
            Text(
                "Queued ${c["queued"] ?: 0} · Done ${c["done"] ?: 0} · Failed ${c["failed"] ?: 0}" +
                    " · Review ${c["needs_review"] ?: 0}" +
                    ((c["waiting_plex"] ?: 0).takeIf { it > 0 }?.let { " · Waiting for Plex $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            val t = status.totals
            if (t.done > 0) {
                val saved = (t.bytesBefore - t.bytesAfter) / 1e9
                Text(
                    String.format(Locale.US, "%d converted, %.1f GB %s, avg %s each", t.done,
                        kotlin.math.abs(saved), if (saved >= 0) "saved" else "added",
                        formatDuration((t.seconds / t.done).toLong())),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Encoder ${status.encoder ?: "?"} · originals ${if (status.originalsMode == "keep") "kept" else "deleted after verification"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            (status.lastError ?: error)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QueueList(items: List<ConverterItem>, viewModel: VideoConversionsViewModel) {
    if (items.isEmpty()) {
        Text("The queue is empty.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title ?: item.path, fontWeight = FontWeight.Medium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val badge = when {
                        item.front && item.source == "play" -> "next — someone tried to play it · "
                        item.front && item.source == "playlist" -> "next — from Convert Next playlist · "
                        item.front -> "next · "
                        item.source == "recent" -> "new · "
                        else -> ""
                    }
                    Text(badge + (item.sectionTitle ?: "") +
                        (if (item.reasons.isNotEmpty()) " · " + item.reasons.joinToString() else ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    item.error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!item.front) {
                    IconButton(onClick = { viewModel.moveToFront(item) }) {
                        Icon(Icons.Default.VerticalAlignTop, contentDescription = "Convert next")
                    }
                }
                IconButton(onClick = { viewModel.remove(item) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove from queue")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun HistoryList(state: VideoConversionsUiState, viewModel: VideoConversionsViewModel) {
    val items = state.history
    var forceItem by remember { mutableStateOf<ConverterItem?>(null) }
    var useItem by remember { mutableStateOf<ConverterItem?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (state.historyTotal > items.size) "Showing ${items.size} of ${state.historyTotal}"
             else "${state.historyTotal} item(s)",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { sortMenu = true }) { Text("Sort: ${state.historySort.label}") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                HistorySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(if (sort == state.historySort) "✓ ${sort.label}" else sort.label) },
                        onClick = { sortMenu = false; viewModel.setHistorySort(sort) },
                    )
                }
            }
        }
    }
    HorizontalDivider()
    if (items.isEmpty()) {
        Text(if (state.historyTotal == 0) "Nothing converted yet." else "Loading…", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(item.title ?: item.path, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                val (label, color) = when (item.state) {
                    "done" -> "✅ Converted" to MaterialTheme.colorScheme.primary
                    "waiting_plex" -> "⏳ Converted — waiting for Plex to pick it up (original kept until then)" to
                        MaterialTheme.colorScheme.secondary
                    "needs_review" -> "⚠ Needs review — original kept" to MaterialTheme.colorScheme.tertiary
                    "failed" -> "❌ Failed — original kept" to MaterialTheme.colorScheme.error
                    "skipped" -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> item.state to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(label, color = color, style = MaterialTheme.typography.bodySmall)
                timingLine(item)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                item.result?.let { r -> resultSummary(r)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                } }
                item.nasPath?.takeIf { it.isNotBlank() }?.let {
                    // Converted: the new file. Otherwise the original, which was kept.
                    Text((if (item.state == "done") "📁 " else "📁 Original: ") + it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
                item.error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
                        overflow = TextOverflow.Ellipsis)
                }
                item.reviewNasPath?.let {
                    Text("🔍 Converted file kept for review: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
                if (item.state == "failed" || item.state == "needs_review") {
                    Row {
                        TextButton(onClick = { viewModel.retry(item, force = false) }) { Text("Retry") }
                        if (item.reviewNasPath != null) {
                            // A converted file exists: use it as-is instead of converting again.
                            TextButton(onClick = { useItem = item }) { Text("Use this file") }
                            TextButton(onClick = { viewModel.deleteReviewCopy(item) }) { Text("Delete review copy") }
                        } else if (item.state == "needs_review") {
                            TextButton(onClick = { forceItem = item }) { Text("Convert anyway") }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        if (items.size < state.historyTotal) {
            item(key = "load-more") {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { viewModel.loadMoreHistory() }) {
                        Text("Load more (${state.historyTotal - items.size} more)")
                    }
                }
            }
        }
    }
    useItem?.let { item ->
        AlertDialog(
            onDismissRequest = { useItem = null },
            title = { Text("Use the reviewed file?") },
            text = {
                Text("The converted file you checked replaces the original:\n${item.reviewNasPath}\n\n" +
                    "It didn't pass every automatic check: ${item.error ?: ""}\n\n" +
                    "Nothing is converted again. It's used only if it's still exactly the file that " +
                    "was converted and the original hasn't changed since. Plex keeps the same title " +
                    "and watch status.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.useReviewCopy(item); useItem = null }) {
                    Text("Use this file")
                }
            },
            dismissButton = { TextButton(onClick = { useItem = null }) { Text("Cancel") } },
        )
    }
    forceItem?.let { item ->
        AlertDialog(
            onDismissRequest = { forceItem = null },
            title = { Text("Convert anyway?") },
            text = {
                Text("The original already has playback errors: ${item.error ?: ""}\n\n" +
                    "The converted file must still pass every other check (same frames, picture " +
                    "and sound match, plays directly). If it does, the damaged original is replaced.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.retry(item, force = true); forceItem = null }) {
                    Text("Convert anyway")
                }
            },
            dismissButton = { TextButton(onClick = { forceItem = null }) { Text("Cancel") } },
        )
    }
}

private fun resultSummary(r: JsonObject): String? {
    fun num(obj: JsonObject?, key: String) = obj?.get(key)?.jsonPrimitive?.doubleOrNull
    val src = r["source_size"]?.jsonPrimitive?.longOrNull ?: return null
    val out = r["output_size"]?.jsonPrimitive?.longOrNull ?: return null
    val verification = runCatching { r["verification"]?.jsonObject }.getOrNull()
    val ssim = num(verification, "ssim_mean")
    val encoder = r["encoder"]?.jsonPrimitive?.content
    return buildList {
        add(String.format(Locale.US, "%.0f → %.0f MB", src / 1e6, out / 1e6))
        encoder?.let { add(it) }
        ssim?.let { add(String.format(Locale.US, "match %.3f", it)) }
    }.joinToString(" · ")
}

/** "Finished Tue 13 Oct, 08:24 · took 11 min (convert 5 min, verify 4 min, copying 1 min)". */
private fun timingLine(item: ConverterItem): String? {
    val finished = item.finishedAt ?: return null
    val took = item.startedAt?.let { (finished - it).toLong() }?.takeIf { it >= 0 }
    val phases = item.result?.let { phaseBreakdown(it) }
    return buildString {
        append(if (item.state == "done" || item.state == "waiting_plex") "Converted " else "Ended ")
        append(formatWhen(finished))
        took?.let { append(" · took ${formatDuration(it)}") }
        phases?.let { append(" ($it)") }
    }
}

private fun phaseBreakdown(r: JsonObject): String? {
    val t = runCatching { r["timings"]?.jsonObject }.getOrNull() ?: return null
    fun sec(key: String) = t[key]?.jsonPrimitive?.doubleOrNull
    val copying = listOfNotNull(sec("copy_in_s"), sec("copy_out_s")).sum()
    return listOfNotNull(
        sec("encode_s")?.let { "convert ${formatDuration(it.toLong())}" },
        sec("verify_s")?.let { "verify ${formatDuration(it.toLong())}" },
        copying.takeIf { it > 0 }?.let { "copying ${formatDuration(it.toLong())}" },
    ).joinToString(", ").ifBlank { null }
}

/** Epoch seconds as local date + time; the year only when it isn't this year. */
private fun formatWhen(epochSeconds: Double): String {
    val t = Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault())
    val pattern = if (t.year == ZonedDateTime.now().year) "EEE d MMM, HH:mm" else "d MMM yyyy, HH:mm"
    return t.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Composable
private fun AddTab(state: VideoConversionsUiState, viewModel: VideoConversionsViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.search(it) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search your Plex library") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.searchResults, key = { it.ratingKey }) { result ->
                SearchRow(result) { viewModel.enqueue(result) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchRow(result: ConverterSearchResult, onConvertNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(result.title + (result.year?.let { " ($it)" } ?: ""), fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = when (result.type) {
                "show", "season" -> "${result.sectionTitle ?: ""} · ${result.leafCount ?: "?"} episodes — queues those that need it"
                else -> {
                    val f = result.files.firstOrNull()
                    val fmt = listOfNotNull(f?.container, f?.videoCodec, f?.audioCodec).joinToString("/")
                    val st = when {
                        f == null -> ""
                        f.state == "done" -> "converted"
                        f.state == "queued" -> "in queue"
                        f.state != null -> f.state
                        f.needsWork -> "needs conversion"
                        else -> "plays directly"
                    }
                    "${result.sectionTitle ?: ""} · $fmt · $st"
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        val canQueue = result.type == "show" || result.type == "season" ||
            result.files.any { it.needsWork && it.state != "done" }
        if (canQueue) {
            TextButton(onClick = onConvertNext) { Text("Convert next") }
        } else {
            Spacer(Modifier.width(8.dp).size(0.dp))
        }
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> String.format(Locale.US, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
}
