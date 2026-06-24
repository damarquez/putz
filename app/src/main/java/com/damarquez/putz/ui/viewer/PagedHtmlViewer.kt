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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
fun PagedHtmlViewer(
    destDir: File,
    pages: List<File>,
    pathPrefix: String,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Couldn't read this file",
) {
    val context = LocalContext.current
    var currentIndex by remember(pages) { mutableIntStateOf(0) }
    var showSearch by remember(pages) { mutableStateOf(false) }
    var searchQuery by remember(pages) { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val searchEngine = remember(pages) {
        PagedHtmlSearchEngine(
            pages = pages,
            scope = coroutineScope,
            currentPageIndex = { currentIndex },
            navigateToPage = { currentIndex = it },
        )
    }

    LaunchedEffect(searchEngine, searchQuery) {
        searchEngine.search(searchQuery)
    }

    if (pages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyMessage)
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) {
                    searchQuery = ""
                    searchEngine.clear()
                }
            }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
        if (showSearch) {
            SearchBar(
                engine = searchEngine,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = {
                    showSearch = false
                    searchQuery = ""
                    searchEngine.clear()
                },
            )
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    // Pinch-to-zoom + pan, native to WebView — no custom gesture handling needed.
                    // builtInZoomControls enables the pinch gesture itself; displayZoomControls
                    // just hides the on-screen +/- buttons so pinch is the only way to zoom.
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // EPUB/MOBI chapter HTML is always written as UTF-8, but WebViewAssetLoader's
                    // response carries no charset, so Chromium's encoding sniffer can fall back to
                    // a non-UTF-8 default and turn multi-byte characters (smart quotes, accents)
                    // into mojibake like "â€œ". Force UTF-8 on both the response and the WebView's
                    // fallback decoder.
                    settings.defaultTextEncodingName = "utf-8"
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            assetLoader.shouldInterceptRequest(request.url)?.apply { encoding = "utf-8" }

                        // Page navigation clears WebView's find state; let the engine re-apply
                        // the active query (and land on the right occurrence) on the new page.
                        override fun onPageFinished(view: WebView, url: String) {
                            searchEngine.onPageLoaded()
                        }
                    }
                }.also { searchEngine.attach(it) }
            },
            update = { webView -> webView.loadUrl(pageUrl) },
        )
        PageNavBar(
            currentIndex = currentIndex,
            pageCount = pages.size,
            onPrevious = { currentIndex-- },
            onNext = { currentIndex++ },
        )
    }
}
