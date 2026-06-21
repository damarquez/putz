package com.damarquez.putz.ui.viewer

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.damarquez.putz.util.EpubExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val EPUB_HTTP_DOMAIN = "appassets.androidplatform.net"
private const val EPUB_PATH_PREFIX = "/epub/"

private data class EpubContents(val destDir: File, val chapters: List<File>)

/**
 * Quick EPUB text preview: extracts the spine and shows it chapter-by-chapter through a WebView
 * (native long-press text selection/copy comes for free). No TOC, search, or within-chapter
 * pagination — just enough to see what the book is and read a bit of it.
 *
 * Chapters are served through [WebViewAssetLoader] on a virtual https:// origin rather than
 * loaded via file:// — Chromium's WebView sandbox denies direct file:// access to app-private
 * storage (ERR_ACCESS_DENIED), and the asset loader also resolves each chapter's relative
 * CSS/image references correctly since it mirrors the extracted directory structure.
 */
@Composable
fun EpubViewer(filePath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val contents by produceState<EpubContents?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            val epubFile = File(filePath)
            val destDir = File(context.cacheDir, "previews/epub_${epubFile.name}")
            val chapters = runCatching { EpubExtractor.extractSpine(epubFile, destDir) }.getOrDefault(emptyList())
            EpubContents(destDir, chapters)
        }
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    val current = contents

    Column(modifier = modifier.fillMaxSize()) {
        when {
            current == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current.chapters.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Couldn't read this EPUB")
            }
            else -> {
                val assetLoader = remember(current.destDir) {
                    WebViewAssetLoader.Builder()
                        .addPathHandler(EPUB_PATH_PREFIX, WebViewAssetLoader.InternalStoragePathHandler(context, current.destDir))
                        .build()
                }
                val chapterUrl = "https://$EPUB_HTTP_DOMAIN$EPUB_PATH_PREFIX" +
                    current.chapters[currentIndex].relativeTo(current.destDir).path.replace(File.separatorChar, '/')

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                            }
                        }
                    },
                    update = { webView -> webView.loadUrl(chapterUrl) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { currentIndex-- }, enabled = currentIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous chapter")
                    }
                    Text("Chapter ${currentIndex + 1} / ${current.chapters.size}")
                    IconButton(onClick = { currentIndex++ }, enabled = currentIndex < current.chapters.size - 1) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next chapter")
                    }
                }
            }
        }
    }
}
