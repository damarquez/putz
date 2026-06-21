package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.damarquez.putz.util.EncryptedFileSignature
import com.damarquez.putz.util.RtfExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Quick RTF preview: strips control words/groups down to plain readable text. No formatting. */
@Composable
fun RtfViewer(filePath: String, modifier: Modifier = Modifier) {
    val content by produceState<String?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (EncryptedFileSignature.isEncrypted(file)) {
                EncryptedFileSignature.MESSAGE
            } else {
                runCatching { truncateForPreview(RtfExtractor.extractText(file)) }
                    .getOrElse { "Couldn't read this file" }
            }
        }
    }

    val text = content
    if (text == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PlainTextViewer(text = text, modifier = modifier)
    }
}
