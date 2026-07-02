package com.damarquez.putz.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File

private const val HTML_PATH_PREFIX = "/html/"

/** Single-file HTML preview, reusing the EPUB/MOBI paged WebView shell for one "page". */
@Composable
fun HtmlViewer(filePath: String, modifier: Modifier = Modifier) {
    val file = File(filePath)
    PagedHtmlViewer(
        destDir = file.parentFile ?: file,
        pages = listOf(file),
        pathPrefix = HTML_PATH_PREFIX,
        sourceFile = file,
        formatLabel = "HTML",
        pageCount = 1,
        modifier = modifier,
    )
}
