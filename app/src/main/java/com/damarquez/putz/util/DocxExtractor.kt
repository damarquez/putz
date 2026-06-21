package com.damarquez.putz.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Minimal DOCX (Office Open XML) reader: unzips `word/document.xml` and extracts paragraph
 * text, dropping all formatting. No headers/footers/footnotes, and table cells are included
 * as plain text rather than laid out as a grid — just enough to see what the document is about.
 */
object DocxExtractor {

    fun extractText(docxFile: File): String {
        ZipFile(docxFile).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            return zip.getInputStream(entry).use { input -> parseDocumentXml(input) }
        }
    }

    private fun parseDocumentXml(input: InputStream): String {
        val out = StringBuilder()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var insideText = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                // Strip any "w:" prefix before comparing — Xml.newPullParser() may or may not have
                // namespace processing on, which changes whether parser.name includes the prefix.
                XmlPullParser.START_TAG -> when (parser.name?.substringAfter(':')) {
                    "t" -> insideText = true
                    "tab" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                }
                XmlPullParser.TEXT -> if (insideText) out.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name?.substringAfter(':')) {
                    "t" -> insideText = false
                    "p" -> out.append('\n')
                }
            }
            eventType = parser.next()
        }
        return out.toString()
    }
}
