package com.damarquez.putz.util

import java.io.File

private const val COMPRESSION_NONE = 1
private const val COMPRESSION_PALMDOC = 2
private const val COMPRESSION_HUFFCDIC = 17480 // 0x4448 ("DH")

/**
 * Minimal Mobipocket (.mobi) / AZW3 (KF8) reader: parses the PalmDB record table, decompresses
 * the text records (PalmDOC LZ77, HUFF/CDIC, or uncompressed), and splits the result into pages
 * on `<mbp:pagebreak>` markers (or returns it as one page when there are none — common for
 * AZW3, whose KF8 layout system doesn't rely on that legacy tag, so it usually comes back as
 * one big page rather than per-chapter pages). No KF8 skeleton/fragment reconstruction, no
 * EXTH metadata, no DRM support — those cases return no pages so the caller can show a
 * fallback message.
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
        val supportedCompression = compression == COMPRESSION_NONE || compression == COMPRESSION_PALMDOC ||
            compression == COMPRESSION_HUFFCDIC
        if (encryptionType != 0 || !supportedCompression) return emptyList()

        val huffCdic = if (compression == COMPRESSION_HUFFCDIC) buildHuffCdicReader(record0, ::record) else null
        val (multibyte, trailerCount) = readTrailingDataConfig(record0)

        val textBuilder = StringBuilder()
        for (i in 1..recordCount) {
            if (i >= numRecords) break
            val trimmed = trimTrailingData(record(i), multibyte, trailerCount)
            val decompressed = when (compression) {
                COMPRESSION_PALMDOC -> decompressPalmDoc(trimmed)
                COMPRESSION_HUFFCDIC -> huffCdic!!.unpack(trimmed)
                else -> trimmed
            }
            textBuilder.append(String(decompressed, Charsets.UTF_8))
        }

        val pageBreak = Regex("<mbp:pagebreak[^>]*/?>", RegexOption.IGNORE_CASE)
        val pages = textBuilder.toString().split(pageBreak).filter { it.isNotBlank() }
            .ifEmpty { listOf(textBuilder.toString()) }

        return pages.mapIndexed { index, pageHtml ->
            File(destDir, "page_$index.html").apply { writeText(pageHtml) }
        }
    }

    private fun buildHuffCdicReader(record0: ByteArray, record: (Int) -> ByteArray): HuffCdicReader {
        val huffOffset = readUInt32(record0, 0x70)
        val huffCount = readUInt32(record0, 0x74)
        val reader = HuffCdicReader()
        reader.loadHuff(record(huffOffset))
        for (i in 1 until huffCount) reader.loadCdic(record(huffOffset + i))
        return reader
    }

    /**
     * Each text record can end with extra "trailing data entries" (e.g. multibyte character
     * overlap) appended after the actual compressed content, which must be stripped before
     * decompression. Only present when the MOBI header is long enough and the format version
     * supports it; see `extra flags` in the MOBI header spec.
     */
    private fun readTrailingDataConfig(record0: ByteArray): Pair<Boolean, Int> {
        if (record0.size < 0x14 + 4) return false to 0
        val mobiHeaderLength = readUInt32(record0, 0x14)
        val mobiVersion = if (record0.size >= 0x68 + 4) readUInt32(record0, 0x68) else 0
        if (mobiHeaderLength < 0xE4 || mobiVersion < 5 || record0.size < 0xF2 + 2) return false to 0
        var flags = readUInt16(record0, 0xF2)
        val multibyte = (flags and 1) != 0
        var trailers = 0
        flags = flags shr 1
        while (flags != 0) {
            if (flags and 1 != 0) trailers++
            flags = flags shr 1
        }
        return multibyte to trailers
    }

    private fun trimTrailingData(data: ByteArray, multibyte: Boolean, trailerCount: Int): ByteArray {
        var end = data.size
        repeat(trailerCount) {
            end -= sizeOfTrailingEntry(data, end)
        }
        if (multibyte && end > 0) {
            end -= (data[end - 1].toInt() and 0x3) + 1
        }
        return data.copyOfRange(0, end.coerceAtLeast(0))
    }

    /** Trailing entry size is itself encoded as a variable-length big-endian base-128 value in the entry's last bytes. */
    private fun sizeOfTrailingEntry(data: ByteArray, end: Int): Int {
        var num = 0
        for (i in maxOf(0, end - 4) until end) {
            val v = data[i].toInt() and 0xFF
            if (v and 0x80 != 0) num = 0
            num = (num shl 7) or (v and 0x7F)
        }
        return num
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
