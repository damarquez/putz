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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.File

private const val PAGED_HTML_DOMAIN = "appassets.androidplatform.net"

/**
 * Shared "list of static HTML files, browsed one at a time through a WebView" shell used by
 * EpubViewer and MobiViewer. Pages are served through [WebViewAssetLoader] on a virtual https://
 * origin rooted at [destDir] — Chromium's WebView sandbox denies direct file:// access to
 * app-private storage (ERR_ACCESS_DENIED) — which also resolves each page's relative CSS/image
 * references correctly since the virtual path mirrors the extracted directory structure.
 */
@Composable
fun PagedHtmlViewer(destDir: File, pages: List<File>, pathPrefix: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var currentIndex by remember(pages) { mutableIntStateOf(0) }

    if (pages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't read this file")
        }
        return
    }

    val assetLoader = remember(destDir, pathPrefix) {
        WebViewAssetLoader.Builder()
            .addPathHandler(pathPrefix, WebViewAssetLoader.InternalStoragePathHandler(context, destDir))
            .build()
    }
    val pageUrl = "https://$PAGED_HTML_DOMAIN$pathPrefix" +
        pages[currentIndex].relativeTo(destDir).path.replace(File.separatorChar, '/')

    Column(modifier = modifier.fillMaxSize()) {
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
            update = { webView -> webView.loadUrl(pageUrl) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { currentIndex-- }, enabled = currentIndex > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
            }
            Text("${currentIndex + 1} / ${pages.size}")
            IconButton(onClick = { currentIndex++ }, enabled = currentIndex < pages.size - 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
            }
        }
    }
}
