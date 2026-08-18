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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Plain text preview: scrollable, selectable, copyable. Truncated for very large files. */
@Composable
fun TxtViewer(filePath: String, modifier: Modifier = Modifier) {
    val content by produceState<String?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (EncryptedFileSignature.isEncrypted(file)) {
                EncryptedFileSignature.MESSAGE
            } else {
                runCatching {
                    truncateForPreview(readTextDetectingCharset(file))
                }.getOrElse { "Couldn't read this file" }
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

/**
 * Text files without a BOM don't declare their charset. Try strict UTF-8 first; if the bytes
 * aren't valid UTF-8 (the common case for Windows-1252/ISO-8859-1 files with accented chars),
 * fall back to Windows-1252, whose single-byte mapping never fails to decode.
 */
private fun readTextDetectingCharset(file: File): String {
    val bytes = file.readBytes()
    val utf8Decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        utf8Decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        val fallback = runCatching { java.nio.charset.Charset.forName("windows-1252") }
            .getOrDefault(Charsets.ISO_8859_1)
        String(bytes, fallback)
    }
}
