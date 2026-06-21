package com.damarquez.putz.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.damarquez.putz.util.EncryptedFileSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Page render scale; "nothing fancy" — just sharp enough to read on a phone screen, no zoom. */
private const val RENDER_SCALE = 2

/**
 * Quick PDF preview: rasterizes a page at a time via the platform PdfRenderer, browsed with
 * Previous/Next. No text selection, search, or outline — just enough to see what it's about.
 */
@Composable
fun PdfViewer(filePath: String, modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var protectedFile by remember { mutableStateOf(false) }

    DisposableEffect(filePath) {
        val file = File(filePath)
        if (EncryptedFileSignature.isEncrypted(file)) {
            protectedFile = true
            return@DisposableEffect onDispose {}
        }

        val descriptor = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
        val r = descriptor?.let { d -> runCatching { PdfRenderer(d) }.getOrNull() }
        if (descriptor == null || r == null || r.pageCount == 0) {
            failed = true
            r?.close()
            descriptor?.close()
        } else {
            renderer = r
            pageCount = r.pageCount
        }
        onDispose {
            renderer?.close()
            descriptor?.close()
        }
    }

    LaunchedEffect(renderer, currentIndex) {
        val r = renderer ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                r.openPage(currentIndex).use { page ->
                    val bmp = Bitmap.createBitmap(
                        page.width * RENDER_SCALE,
                        page.height * RENDER_SCALE,
                        Bitmap.Config.ARGB_8888,
                    )
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val page = bitmap
            when {
                protectedFile -> Text(EncryptedFileSignature.MESSAGE)
                failed -> Text("Couldn't read this file")
                page == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (pageCount > 0) {
            PageNavBar(
                currentIndex = currentIndex,
                pageCount = pageCount,
                onPrevious = { currentIndex-- },
                onNext = { currentIndex++ },
            )
        }
    }
}
