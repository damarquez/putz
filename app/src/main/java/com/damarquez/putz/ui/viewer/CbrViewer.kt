package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.damarquez.putz.util.CbrExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class CbrContents(val destDir: File, val pages: List<File>)

/**
 * Quick CBR (comic book RAR) preview: extracts the image pages in archive order and browses
 * them with the same pinch-zoom/pan as the image viewer, plus Previous/Next.
 */
@Composable
fun CbrViewer(filePath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val contents by produceState<CbrContents?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            val cbrFile = File(filePath)
            val destDir = File(context.cacheDir, "previews/cbr_${cbrFile.name}")
            val pages = runCatching { CbrExtractor.extractImages(cbrFile, destDir) }.getOrDefault(emptyList())
            CbrContents(destDir, pages)
        }
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    val current = contents

    when {
        current == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        current.pages.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't read this file")
        }
        else -> Column(modifier = modifier.fillMaxSize()) {
            ImageViewer(
                filePath = current.pages[currentIndex].absolutePath,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            PageNavBar(
                currentIndex = currentIndex,
                pageCount = current.pages.size,
                onPrevious = { currentIndex-- },
                onNext = { currentIndex++ },
            )
        }
    }
}
