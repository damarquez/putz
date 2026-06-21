package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Cap how much text we load into one Compose Text — this is a quick preview, not a reader. */
private const val MAX_PREVIEW_CHARS = 200_000

/** Plain text preview: scrollable, selectable, copyable. Truncated for very large files. */
@Composable
fun TxtViewer(filePath: String, modifier: Modifier = Modifier) {
    val content by produceState<String?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(filePath)
                val text = file.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (text.length > MAX_PREVIEW_CHARS) {
                    text.take(MAX_PREVIEW_CHARS) + "\n\n[Preview truncated]"
                } else {
                    text
                }
            }.getOrElse { "Couldn't read this file" }
        }
    }

    val text = content
    if (text == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        SelectionContainer(modifier.fillMaxSize()) {
            Text(
                text = text,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}
