package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Cap how much text we load into one Compose Text — this is a quick preview, not a reader. */
private const val MAX_PREVIEW_CHARS = 200_000

fun truncateForPreview(text: String): String =
    if (text.length > MAX_PREVIEW_CHARS) text.take(MAX_PREVIEW_CHARS) + "\n\n[Preview truncated]" else text

/** Shared scrollable, selectable plain-text shell used by TxtViewer and RtfViewer. */
@Composable
fun PlainTextViewer(text: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier.fillMaxSize()) {
        Text(
            text = text,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}
