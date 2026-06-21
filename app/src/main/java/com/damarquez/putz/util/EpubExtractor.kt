package com.damarquez.putz.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Unzips an EPUB and returns its spine documents (chapters) in reading order. Resource
 * paths are preserved on disk relative to the OPF so each chapter's own CSS/images still
 * resolve when loaded via file://. No nav/TOC parsing — chapters are just spine order.
 */
object EpubExtractor {

    fun extractSpine(epubFile: File, destDir: File): List<File> {
        destDir.mkdirs()
        ZipFile(epubFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        val opfPath = parseContainerForOpfPath(File(destDir, "META-INF/container.xml")) ?: return emptyList()
        val opfFile = File(destDir, opfPath)
        val opfDir = opfFile.parentFile ?: destDir
        val (manifest, spineIds) = parseOpf(opfFile)

        return spineIds.mapNotNull { id -> manifest[id]?.let { href -> File(opfDir, href) } }
            .filter { it.exists() }
    }

    private fun parseContainerForOpfPath(containerFile: File): String? {
        if (!containerFile.exists()) return null
        val parser = Xml.newPullParser()
        containerFile.inputStream().use { input ->
            parser.setInput(input, null)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return parser.getAttributeValue(null, "full-path")
                }
                eventType = parser.next()
            }
        }
        return null
    }

    /** Returns (manifest item id -> href) for XHTML items, and the spine's ordered list of item ids. */
    private fun parseOpf(opfFile: File): Pair<Map<String, String>, List<String>> {
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        if (!opfFile.exists()) return manifest to spine

        val parser = Xml.newPullParser()
        opfFile.inputStream().use { input ->
            parser.setInput(input, null)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val mediaType = parser.getAttributeValue(null, "media-type")
                            if (id != null && href != null &&
                                (mediaType == "application/xhtml+xml" || mediaType == "text/html")
                            ) {
                                manifest[id] = href
                            }
                        }
                        "itemref" -> {
                            parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return manifest to spine
    }
}
