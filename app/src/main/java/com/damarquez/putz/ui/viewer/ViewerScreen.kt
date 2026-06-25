package com.damarquez.putz.ui.viewer

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Dispatches to the in-app preview screen for [kind]. Add a branch here when a new ViewerKind is introduced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    kind: ViewerKind,
    title: String,
    filePath: String,
    onNavigateUp: () -> Unit,
) {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    // Goes through the back dispatcher (not onNavigateUp directly) so a viewer's
                    // own BackHandler (e.g. PagedHtmlViewer undoing in-book link navigation) gets
                    // first say.
                    IconButton(onClick = { backDispatcher?.onBackPressed() ?: onNavigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Always exits, regardless of any in-viewer back stack (e.g. WebView
                    // history) — unlike the back arrow/system back, which let the viewer undo a
                    // step first.
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Filled.Close, contentDescription = "Close viewer")
                    }
                },
            )
        },
    ) { padding ->
        when (kind) {
            ViewerKind.IMAGE -> ImageViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.EPUB -> EpubViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.MOBI -> MobiViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.PDF -> PdfViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.TXT -> TxtViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.CBR -> CbrViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.RTF -> RtfViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.DOCX -> DocxViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.DOC -> DocViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.AUDIO -> AudioViewer(filePath = filePath, modifier = Modifier.padding(padding))
        }
    }
}
