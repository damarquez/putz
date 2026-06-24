package com.damarquez.putz.ui.viewer

import android.webkit.WebView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Current find result: 1-based [activeIndex] of [totalCount] matches across the whole book (0/0 when none). */
data class SearchMatch(val activeIndex: Int, val totalCount: Int)

/**
 * Plugs a viewer's content into the [SearchBar] UI. A format gains search by implementing this
 * against whatever rendering surface/document structure it uses — the search bar and its
 * navigation logic stay the same regardless of which engine backs it.
 */
interface ViewerSearchEngine {
    fun search(query: String)
    fun next()
    fun previous()
    fun clear()
    val matchState: State<SearchMatch?>
}

/**
 * [ViewerSearchEngine] for a book split across multiple static HTML [pages] (EPUB chapters,
 * MOBI pagebreak-split pages). A single page's WebView can only find matches within its own
 * loaded document, so this engine pre-counts matches per page by stripping each page's HTML and
 * scanning the plain text, then drives cross-page navigation itself: jumping [navigateToPage]
 * when the next/previous match lives on a different page, and asking the WebView mounted for
 * that page to highlight the right in-page occurrence once it has loaded (see [onPageLoaded]).
 */
class PagedHtmlSearchEngine(
    private val pages: List<File>,
    private val scope: CoroutineScope,
    private val currentPageIndex: () -> Int,
    private val navigateToPage: (Int) -> Unit,
) : ViewerSearchEngine {
    private var query: String = ""
    private val pageTextCache = arrayOfNulls<String>(pages.size)
    private var pageMatchCounts = IntArray(pages.size)
    private var totalMatches = 0
    private var activeGlobalIndex = -1
    private var pendingOccurrenceOnLoad: Int? = null
    private var pendingStepsOnceCounted: Int? = null
    private var attachedWebView: WebView? = null
    private var searchJob: Job? = null

    private val matchStateInternal = mutableStateOf<SearchMatch?>(null)
    override val matchState: State<SearchMatch?> = matchStateInternal

    /** Call once, when the page's WebView instance is created. */
    fun attach(webView: WebView) {
        attachedWebView = webView
        webView.setFindListener { _, _, isDoneCounting ->
            if (isDoneCounting) {
                pendingStepsOnceCounted?.let { steps ->
                    pendingStepsOnceCounted = null
                    repeat(steps) { webView.findNext(true) }
                }
            }
        }
    }

    /** Call from the WebViewClient once a (re)loaded page has finished rendering. */
    fun onPageLoaded() {
        val occurrence = pendingOccurrenceOnLoad
        pendingOccurrenceOnLoad = null
        when {
            occurrence != null -> highlightOccurrenceOnCurrentPage(occurrence)
            query.isNotEmpty() -> attachedWebView?.findAllAsync(query)
        }
    }

    override fun search(query: String) {
        this.query = query
        searchJob?.cancel()
        if (query.isEmpty()) {
            clear()
            return
        }
        searchJob = scope.launch {
            val counts = withContext(Dispatchers.IO) {
                IntArray(pages.size) { i -> countOccurrences(textOf(i), query) }
            }
            pageMatchCounts = counts
            totalMatches = counts.sum()
            if (totalMatches == 0) {
                activeGlobalIndex = -1
                matchStateInternal.value = SearchMatch(0, 0)
                attachedWebView?.clearMatches()
                return@launch
            }
            val cur = currentPageIndex()
            val startIndex = (cur until pages.size).firstOrNull { pageMatchCounts[it] > 0 }
                ?.let(::offsetBeforePage)
                ?: 0
            goTo(startIndex)
        }
    }

    override fun next() {
        if (totalMatches == 0) return
        goTo((activeGlobalIndex + 1) % totalMatches)
    }

    override fun previous() {
        if (totalMatches == 0) return
        goTo((activeGlobalIndex - 1 + totalMatches) % totalMatches)
    }

    override fun clear() {
        searchJob?.cancel()
        query = ""
        pageMatchCounts = IntArray(pages.size)
        totalMatches = 0
        activeGlobalIndex = -1
        pendingOccurrenceOnLoad = null
        pendingStepsOnceCounted = null
        matchStateInternal.value = null
        attachedWebView?.clearMatches()
    }

    private fun goTo(globalIndex: Int) {
        activeGlobalIndex = globalIndex
        matchStateInternal.value = SearchMatch(globalIndex + 1, totalMatches)
        var remaining = globalIndex
        var pageIndex = 0
        while (pageMatchCounts[pageIndex] <= remaining) {
            remaining -= pageMatchCounts[pageIndex]
            pageIndex++
        }
        if (currentPageIndex() == pageIndex) {
            highlightOccurrenceOnCurrentPage(remaining)
        } else {
            pendingOccurrenceOnLoad = remaining
            navigateToPage(pageIndex)
        }
    }

    private fun highlightOccurrenceOnCurrentPage(occurrenceIndex: Int) {
        val webView = attachedWebView ?: return
        pendingStepsOnceCounted = occurrenceIndex
        webView.findAllAsync(query)
    }

    private fun textOf(pageIndex: Int): String =
        pageTextCache[pageIndex] ?: runCatching { stripHtml(pages[pageIndex].readText()) }
            .getOrDefault("")
            .also { pageTextCache[pageIndex] = it }

    private fun stripHtml(html: String): String = html.replace(Regex("<[^>]*>"), " ")

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(needle, index, ignoreCase = true)
            if (index == -1) break
            count++
            index += needle.length
        }
        return count
    }

    private fun offsetBeforePage(pageIndex: Int): Int =
        (0 until pageIndex).sumOf { pageMatchCounts[it] }
}

/** Query field + prev/next + match count + close, driven by any [ViewerSearchEngine]. */
@Composable
fun SearchBar(
    engine: ViewerSearchEngine,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val match by engine.matchState
    val hasMatches = (match?.totalCount ?: 0) > 0

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search") },
        )
        Text(
            text = match?.let { "${it.activeIndex}/${it.totalCount}" }.orEmpty(),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = engine::previous, enabled = hasMatches) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(onClick = engine::next, enabled = hasMatches) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close search")
        }
    }
}
