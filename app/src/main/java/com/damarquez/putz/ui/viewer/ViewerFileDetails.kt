package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Page-count engines mirroring the sidekick daemon's `page_estimator.py`, so a book shows the
 * same "~N pages" figure here as it does once synced into the Calibre library. PDF page count is
 * exact (the platform PdfRenderer already knows it) and needs no estimator.
 */
object PageCountEstimator {

    /** Mirrors `_get_epub_page_count`: word count per spine chapter, with an image-heavy fallback. */
    fun estimateEpub(pages: List<File>, destDir: File, wordsPerPage: Int = 250): Int {
        var totalWords = 0
        var imagePages = 0
        for (page in pages) {
            val html = runCatching { page.readText() }.getOrDefault("")
            val words = countWords(stripHtml(html))
            val imgs = countImgTags(html)
            totalWords += words
            when {
                imgs > 0 && words < 20 -> imagePages += 1
                imgs > 0 && words < imgs * 20 -> imagePages += imgs
            }
        }
        val avgWords = if (pages.isNotEmpty()) totalWords / pages.size else 0
        if (avgWords < 30) {
            val assetImages = countImageAssets(destDir)
            if (assetImages > 1) imagePages = maxOf(imagePages, assetImages - 1)
        }
        val wordPages = totalWords / wordsPerPage
        return maxOf(1, maxOf(wordPages, imagePages))
    }

    /** Mirrors `_get_mobi_page_count`: word count across the (pagebreak-split) extracted pages. */
    fun estimateMobi(pages: List<File>, wordsPerPage: Int = 250): Int {
        var totalWords = 0
        var totalImgs = 0
        for (page in pages) {
            val html = runCatching { page.readText() }.getOrDefault("")
            totalWords += countWords(stripHtml(html))
            totalImgs += countImgTags(html)
        }
        val wordPages = totalWords / wordsPerPage
        return maxOf(1, maxOf(wordPages, totalImgs))
    }

    private fun countImageAssets(destDir: File): Int {
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        return destDir.walkTopDown().count { it.isFile && it.extension.lowercase() in imageExtensions }
    }

    private fun stripHtml(html: String): String = html.replace(Regex("<[^>]*>"), " ")

    private fun countWords(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private val imgTagRegex = Regex("<img\\b", RegexOption.IGNORE_CASE)
    private fun countImgTags(html: String): Int = imgTagRegex.findAll(html).count()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Name/size/format/page-count summary for whatever file is currently open in a viewer. */
@Composable
fun FileDetailsDialog(
    file: File,
    formatLabel: String,
    pageCount: Int?,
    pageCountIsEstimate: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Details") },
        text = {
            Column {
                DetailRow(label = "Name", value = file.name)
                DetailRow(label = "Size", value = formatBytes(file.length()))
                DetailRow(label = "Format", value = formatLabel)
                if (pageCount != null) {
                    DetailRow(label = if (pageCountIsEstimate) "Estimated pages" else "Pages", value = "$pageCount")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
