package com.damarquez.putz.util

import java.io.File

/**
 * Minimal Mobipocket (.mobi) reader: parses the PalmDB record table, decompresses the text
 * records (PalmDOC LZ77 or uncompressed), and splits the result into pages on
 * `<mbp:pagebreak>` markers (or returns it as one page when there are none). No KF8/EXTH
 * metadata, no HUFF/CDIC compression, no DRM support — those cases return no pages so the
 * caller can show a fallback message.
 */
object MobiExtractor {

    fun extractPages(mobiFile: File, destDir: File): List<File> {
        destDir.mkdirs()
        val bytes = mobiFile.readBytes()
        val numRecords = readUInt16(bytes, 76)
        if (numRecords <= 0) return emptyList()
        val offsets = IntArray(numRecords) { i -> readUInt32(bytes, 78 + i * 8) }

        fun record(index: Int): ByteArray {
            val start = offsets[index]
            val end = if (index + 1 < numRecords) offsets[index + 1] else bytes.size
            return bytes.copyOfRange(start, end)
        }

        val record0 = record(0)
        val compression = readUInt16(record0, 0)
        val recordCount = readUInt16(record0, 8)
        val encryptionType = readUInt16(record0, 12)
        if (encryptionType != 0 || (compression != 1 && compression != 2)) return emptyList()

        val textBuilder = StringBuilder()
        for (i in 1..recordCount) {
            if (i >= numRecords) break
            val raw = record(i)
            val decompressed = if (compression == 2) decompressPalmDoc(raw) else raw
            textBuilder.append(String(decompressed, Charsets.UTF_8))
        }

        val pageBreak = Regex("<mbp:pagebreak[^>]*/?>", RegexOption.IGNORE_CASE)
        val pages = textBuilder.toString().split(pageBreak).filter { it.isNotBlank() }
            .ifEmpty { listOf(textBuilder.toString()) }

        return pages.mapIndexed { index, pageHtml ->
            File(destDir, "page_$index.html").apply { writeText(pageHtml) }
        }
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readUInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    /** Classic PalmDOC LZ77-style decompression: literal runs, back-references, and the space+xor-0x80 shorthand. */
    private fun decompressPalmDoc(data: ByteArray): ByteArray {
        var buf = ByteArray(maxOf(data.size * 4, 16))
        var len = 0
        fun ensure(extra: Int) {
            if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
        }

        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c == 0 -> { ensure(1); buf[len++] = c.toByte() }
                c in 1..8 -> {
                    ensure(c)
                    for (j in 0 until c) {
                        if (i < data.size) { buf[len++] = data[i]; i++ }
                    }
                }
                c in 0x09..0x7F -> { ensure(1); buf[len++] = c.toByte() }
                c in 0x80..0xBF -> {
                    if (i >= data.size) break
                    val c2 = data[i].toInt() and 0xFF
                    i++
                    val combined = ((c and 0x3F) shl 8) or c2
                    val distance = combined shr 3
                    val copyLen = (combined and 0x07) + 3
                    ensure(copyLen)
                    var srcPos = len - distance
                    repeat(copyLen) {
                        buf[len++] = if (srcPos in 0 until len) buf[srcPos] else 0
                        srcPos++
                    }
                }
                else -> { // 0xC0..0xFF: space + (c xor 0x80)
                    ensure(2)
                    buf[len++] = ' '.code.toByte()
                    buf[len++] = (c xor 0x80).toByte()
                }
            }
        }
        return buf.copyOf(len)
    }
}
