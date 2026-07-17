package com.damarquez.putz.ui.viewer

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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

// Some EPUB/MOBI producers write chapter filenames containing spaces/parentheses (e.g. an
// Adobe InDesign or Wildside Press-style retail export: "Here (retail).xhtml"). WebViewAssetLoader
// serves pages through a real https:// URL, and a raw unencoded space/paren there makes Chromium's
// URL parser mis-parse the request, which then misses WebViewAssetLoader's path match and falls
// through to an actual (failing) network fetch — surfacing as WebView's "webpage not available"
// page instead of any Kotlin-level error. Percent-encode each path segment (never the "/"
// separators) to keep the URL valid while still resolving to the right on-disk file.
private fun File.encodedRelativePathIn(base: File): String =
    relativeTo(base).path
        .replace(File.separatorChar, '/')
        .split('/')
        .joinToString("/") { Uri.encode(it) }

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
    sourceFile: File,
    formatLabel: String,
    pageCount: Int?,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Couldn't read this file",
) {
    val context = LocalContext.current
    var currentIndex by remember(pages) { mutableIntStateOf(0) }
    var showSearch by remember(pages) { mutableStateOf(false) }
    var searchQuery by remember(pages) { mutableStateOf("") }
    var showDetails by remember(pages) { mutableStateOf(false) }
    var webViewRef by remember(pages) { mutableStateOf<WebView?>(null) }
    var canGoBackInWebView by remember(pages) { mutableStateOf(false) }
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

    // A tapped in-book link (e.g. a title page's link to a chapter) navigates the WebView itself
    // without touching currentIndex, so back must undo that navigation before leaving the viewer.
    BackHandler(enabled = canGoBackInWebView) {
        webViewRef?.goBack()
    }

    val assetLoader = remember(destDir, pathPrefix) {
        WebViewAssetLoader.Builder()
            .addPathHandler(pathPrefix, WebViewAssetLoader.InternalStoragePathHandler(context, destDir))
            .build()
    }
    val pageUrl = "https://$PAGED_HTML_DOMAIN$pathPrefix" +
        pages[currentIndex].encodedRelativePathIn(destDir)

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
            IconButton(onClick = { showDetails = true }) {
                Icon(Icons.Filled.Info, contentDescription = "File details")
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
                                ?.let(::fixSelfClosingScriptTags)

                        // Page navigation clears WebView's find state; let the engine re-apply
                        // the active query (and land on the right occurrence) on the new page.
                        override fun onPageFinished(view: WebView, url: String) {
                            searchEngine.onPageLoaded()
                            canGoBackInWebView = view.canGoBack()
                            // A tapped in-book link navigates the WebView directly, bypassing
                            // currentIndex. If the visited URL matches a known page, resync so
                            // the page counter/PageNavBar reflect what's actually on screen.
                            val visitedPath = url.substringBefore('#')
                            val matchedIndex = pages.indexOfFirst { page ->
                                visitedPath.endsWith(page.encodedRelativePathIn(destDir))
                            }
                            if (matchedIndex >= 0) currentIndex = matchedIndex
                        }
                    }
                }.also {
                    webViewRef = it
                    searchEngine.attach(it)
                }
            },
            update = { webView -> if (webView.url != pageUrl) webView.loadUrl(pageUrl) },
        )
        PageNavBar(
            currentIndex = currentIndex,
            pageCount = pages.size,
            onPrevious = { currentIndex-- },
            onNext = { currentIndex++ },
        )
    }

    if (showDetails) {
        FileDetailsDialog(
            file = sourceFile,
            formatLabel = formatLabel,
            pageCount = pageCount,
            pageCountIsEstimate = true,
            onDismiss = { showDetails = false },
        )
    }
}

private val SELF_CLOSING_SCRIPT_REGEX = Regex("""<script\b([^>]*?)/>""", RegexOption.IGNORE_CASE)

/**
 * Chromium's HTML5 parser treats `<script>` as a raw-text element and only exits on a
 * literal `</script>` — it does not honor XML self-closing syntax. Some XHTML producers
 * (e.g. Kobo-style EPUB exports) emit `<script src="..."/>`, which is valid XML but invalid
 * HTML5: the parser swallows everything after it (including the entire `<body>`) as inert
 * script text, rendering the page blank while navigation still works fine. Scripts never
 * execute in this viewer (javaScriptEnabled = false), so rewriting them to an explicitly
 * closed, empty tag is always safe.
 */
private fun fixSelfClosingScriptTags(response: WebResourceResponse): WebResourceResponse {
    val mimeType = response.mimeType ?: return response
    if (!mimeType.contains("html")) return response
    val original = response.data ?: return response
    // `.use {}` closes `original` once this finishes reading it — response.data must always be
    // replaced with a fresh stream below, even when no self-closing tag is found, or Chromium
    // ends up reading from the now-closed original and every page fails with "Stream Closed".
    val html = original.use { it.readBytes() }.toString(Charsets.UTF_8)
    val fixed = if (SELF_CLOSING_SCRIPT_REGEX.containsMatchIn(html)) {
        SELF_CLOSING_SCRIPT_REGEX.replace(html) { match -> "<script${match.groupValues[1]}></script>" }
    } else {
        html
    }
    response.data = ByteArrayInputStream(fixed.toByteArray(Charsets.UTF_8))
    return response
}
