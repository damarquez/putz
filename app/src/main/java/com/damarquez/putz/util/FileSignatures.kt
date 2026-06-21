package com.damarquez.putz.util

import java.io.File
import java.util.zip.ZipFile

/**
 * Detects a file's real format from its content (magic bytes / zip container structure) rather
 * than trusting its extension — old ebook/scene releases frequently mislabel plain text/RTF as
 * .doc, or use some other wrong extension entirely. Returns null when sniffing is inconclusive
 * (plain text has no fixed signature, and anything we don't recognize), so callers should fall
 * back to extension-based detection in that case.
 */
object FileSignatures {

    const val PDF = "PDF"
    const val RTF = "RTF"
    const val DOC = "DOC"
    const val MOBI = "MOBI"
    const val IMAGE = "IMAGE"
    const val EPUB = "EPUB"
    const val DOCX = "DOCX"

    fun detect(file: File): String? {
        val head = readHead(file)
        if (head == null || head.isEmpty()) return null

        // Deliberately not sniffing RAR -> CBR here: RAR's magic bytes only say "this is a RAR
        // archive", not "this is meant to be a comic" — unlike RTF-vs-binary-DOC, that's a
        // semantic distinction the extension is actually the right signal for. Misdetecting a
        // generic .rar as CBR would route it into a viewer that can only succeed if it happens
        // to contain images, with no fallback to opening it externally otherwise.
        return when {
            startsWith(head, "%PDF-") -> PDF
            startsWith(head, "{\\rtf") -> RTF
            startsWithBytes(head, CFB_SIGNATURE) -> DOC
            head.size >= 68 && safeAscii(head, 60, 8) == "BOOKMOBI" -> MOBI
            startsWithBytes(head, JPEG_SIGNATURE) || startsWithBytes(head, PNG_SIGNATURE) ||
                startsWith(head, "GIF87a") || startsWith(head, "GIF89a") || startsWith(head, "BM") ||
                isWebp(head) -> IMAGE
            startsWithBytes(head, ZIP_SIGNATURE) -> detectZipFormat(file)
            else -> null
        }
    }

    private fun isWebp(head: ByteArray): Boolean =
        head.size >= 12 && startsWith(head, "RIFF") && safeAscii(head, 8, 4) == "WEBP"

    private fun detectZipFormat(file: File): String? = try {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("META-INF/container.xml") != null -> EPUB
                zip.getEntry("word/document.xml") != null -> DOCX
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun readHead(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(512)
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        }
    }.getOrNull()

    private fun safeAscii(data: ByteArray, offset: Int, length: Int): String? =
        runCatching { String(data, offset, length, Charsets.US_ASCII) }.getOrNull()

    private fun startsWith(data: ByteArray, prefix: String): Boolean {
        if (data.size < prefix.length) return false
        for (i in prefix.indices) if (data[i].toInt().toChar() != prefix[i]) return false
        return true
    }

    private fun startsWithBytes(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private val CFB_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val ZIP_SIGNATURE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04)
}
