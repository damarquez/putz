package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.damarquez.putz.util.EncryptedFileSignature
import com.damarquez.putz.util.EpubExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val EPUB_PATH_PREFIX = "/epub/"

private data class EpubContents(
    val destDir: File,
    val chapters: List<File>,
    val errorMessage: String? = null,
    val pageCount: Int? = null,
)

/**
 * Quick EPUB text preview: extracts the spine and browses it chapter-by-chapter (native
 * long-press text selection/copy comes for free from the WebView). No TOC, search, or
 * within-chapter pagination — just enough to see what the book is and read a bit of it.
 */
@Composable
fun EpubViewer(filePath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val contents by produceState<EpubContents?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            val epubFile = File(filePath)
            if (EncryptedFileSignature.isEncrypted(epubFile)) {
                EpubContents(epubFile, emptyList(), EncryptedFileSignature.MESSAGE)
            } else {
                val destDir = File(context.cacheDir, "previews/epub_${epubFile.name}")
                // Own destDir end-to-end: don't rely on FilesViewModel's startup cache wipe
                // (which runs in its own coroutine and can race a preview triggered right after
                // screen entry, deleting files out from under — or just after — this extraction).
                destDir.deleteRecursively()
                val chapters = runCatching { EpubExtractor.extractSpine(epubFile, destDir) }.getOrDefault(emptyList())
                val pageCount = if (chapters.isEmpty()) null else PageCountEstimator.estimateEpub(chapters, destDir)
                EpubContents(destDir, chapters, pageCount = pageCount)
            }
        }
    }

    val current = contents
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PagedHtmlViewer(
            destDir = current.destDir,
            pages = current.chapters,
            pathPrefix = EPUB_PATH_PREFIX,
            sourceFile = File(filePath),
            formatLabel = "EPUB",
            pageCount = current.pageCount,
            modifier = modifier,
            emptyMessage = current.errorMessage ?: "Couldn't read this file",
        )
    }
}
