package com.damarquez.putz.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.damarquez.putz.data.model.PutioFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Snapshot of a size-fetching pass over a candidate file list — [sizesById] holds whatever
 * size is known for each file so far (real for local/LAN/archive-sourced files immediately;
 * for synced stubs, the raw stub size until resolved). [resolvedCount]/[totalToResolve] track
 * only the synced stubs that actually needed a network fetch.
 */
data class FileSizeProgress(
    val sizesById: Map<Long, Long>,
    val resolvedCount: Int,
    val totalToResolve: Int,
) {
    val isComplete: Boolean get() = resolvedCount >= totalToResolve

    fun totalFor(files: Collection<PutioFile>): Long = files.sumOf { sizesById[it.id] ?: it.size }
}

/**
 * Progressively resolves real sizes for synced stubs (batches of 5, mirroring
 * FilesViewModel.enrichSyncedFileSizes), emitting an updated snapshot after each batch so
 * callers can show a live-updating total without blocking on the full fetch. Local/LAN/
 * archive-sourced files are already accurate and never trigger a fetch.
 */
suspend fun computeSizeProgress(
    files: List<PutioFile>,
    readStubFileSize: suspend (PutioFile) -> Long?,
    onProgress: (FileSizeProgress) -> Unit,
) {
    val sizes = files.associateTo(mutableMapOf()) { it.id to it.size }
    val pending = files.filter { it.isSynced }
    onProgress(FileSizeProgress(sizes.toMap(), 0, pending.size))
    var resolved = 0
    pending.chunked(5).forEach { chunk ->
        coroutineScope {
            chunk.map { stub ->
                async {
                    val size = readStubFileSize(stub)
                    if (size != null && size > 0) sizes[stub.id] = size
                }
            }.awaitAll()
        }
        resolved += chunk.size
        onProgress(FileSizeProgress(sizes.toMap(), resolved, pending.size))
    }
}

@Composable
fun rememberSizeProgress(files: List<PutioFile>, readStubFileSize: suspend (PutioFile) -> Long?): FileSizeProgress? {
    var progress by remember(files) { mutableStateOf<FileSizeProgress?>(null) }
    LaunchedEffect(files) { computeSizeProgress(files, readStubFileSize) { progress = it } }
    return progress
}

fun formatByteSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** Suffix to append next to a "N of M selected" line: the live total, plus a "fetching
 * sizes…" note while synced stubs are still being resolved in the background. */
fun sizeProgressSuffix(progress: FileSizeProgress?, selected: Collection<PutioFile>): String {
    if (progress == null) return ""
    val sizeText = formatByteSize(progress.totalFor(selected))
    return if (progress.isComplete) " · $sizeText"
    else " · $sizeText so far (fetching sizes: ${progress.resolvedCount}/${progress.totalToResolve})"
}

/** Standalone "Total size: …" line for the final send/confirm screen — same live-updating
 * total as [sizeProgressSuffix], without the leading separator meant for appending inline. */
fun sizeSummaryText(progress: FileSizeProgress?, files: Collection<PutioFile>): String? {
    if (progress == null) return null
    val sizeText = formatByteSize(progress.totalFor(files))
    return if (progress.isComplete) "Total size: $sizeText"
    else "Total size: $sizeText so far (fetching sizes: ${progress.resolvedCount}/${progress.totalToResolve})"
}
