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
import com.damarquez.putz.util.MobiExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MOBI_PATH_PREFIX = "/mobi/"

private data class MobiContents(val destDir: File, val pages: List<File>)

/**
 * Quick MOBI / AZW3 text preview: decompresses the PalmDOC text records and browses the result
 * page-by-page (native long-press text selection/copy comes for free from the WebView).
 * DRM-protected or HUFF/CDIC-compressed files fall back to "Couldn't read this file".
 */
@Composable
fun MobiViewer(filePath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val contents by produceState<MobiContents?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            val mobiFile = File(filePath)
            val destDir = File(context.cacheDir, "previews/mobi_${mobiFile.name}")
            val pages = runCatching { MobiExtractor.extractPages(mobiFile, destDir) }.getOrDefault(emptyList())
            MobiContents(destDir, pages)
        }
    }

    val current = contents
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PagedHtmlViewer(destDir = current.destDir, pages = current.pages, pathPrefix = MOBI_PATH_PREFIX, modifier = modifier)
    }
}
