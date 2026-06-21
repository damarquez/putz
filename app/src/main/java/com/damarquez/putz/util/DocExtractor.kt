package com.damarquez.putz.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

private val WINDOWS_1252 = Charset.forName("windows-1252")

/**
 * Minimal legacy .doc (pre-2007 binary Word format) reader: walks the OLE2 container (via
 * [CfbReader]) to the "WordDocument" stream, parses the FIB header to find the piece table
 * (Clx) in the "0Table"/"1Table" stream, then decodes each piece's text run -- UTF-16LE for
 * "unicode" pieces, or Windows-1252 for "compressed" 8-bit pieces (Word always uses a
 * Windows-1252-like mapping for compressed text, NOT the document's authoring-platform
 * codepage -- verified against real Mac- and Windows-authored sample files; using the
 * platform's native codepage there silently produces wrong smart quotes/punctuation).
 * No formatting, headers/footers, tables-as-tables, or embedded objects -- field codes
 * (e.g. page numbers, TOC, hyperlink targets) are stripped down to just their visible result.
 */
object DocExtractor {

    // Word's literal in-text control characters for field codes and paragraph-like breaks.
    private const val FIELD_BEGIN_CODE = 0x13
    private const val FIELD_SEPARATOR_CODE = 0x14
    private const val FIELD_END_CODE = 0x15
    private const val CELL_MARK_CODE = 0x07
    private const val LINE_BREAK_CODE = 0x0B
    private const val PAGE_BREAK_CODE = 0x0C

    fun extractText(docFile: File): String {
        val bytes = docFile.readBytes()
        // Old ebook/scene "ANSI .doc" releases are frequently plain RTF saved with a .doc
        // extension rather than the real OLE2 binary format — sniff content, not just extension.
        if (looksLikeRtf(bytes)) return RtfExtractor.extractText(docFile)

        val cfb = CfbReader(bytes)
        val wordDocument = cfb.openStream("WordDocument") ?: return ""
        val buf = ByteBuffer.wrap(wordDocument).order(ByteOrder.LITTLE_ENDIAN)

        val wIdent = buf.getShort(0).toInt() and 0xFFFF
        require(wIdent == 0xA5EC) { "not a Word binary FIB" }
        val flags = buf.getShort(0x0A).toInt() and 0xFFFF
        val fEncrypted = (flags shr 8) and 1
        val fWhichTblStm = (flags shr 9) and 1
        if (fEncrypted != 0) return ""

        val csw = buf.getShort(0x20).toInt() and 0xFFFF
        var off = 0x20 + 2 + csw * 2
        val cslw = buf.getShort(off).toInt() and 0xFFFF
        off += 2 + cslw * 4
        off += 2 // cbRgFcLcb itself
        val fibRgFcLcbStart = off
        val fcClxOffset = fibRgFcLcbStart + 33 * 8 // fcClx is a fixed index into FibRgFcLcb97+
        val fcClx = buf.getInt(fcClxOffset)
        val lcbClx = buf.getInt(fcClxOffset + 4)

        val tableStream = cfb.openStream(if (fWhichTblStm != 0) "1Table" else "0Table") ?: return ""
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > tableStream.size) return ""
        val clx = tableStream.copyOfRange(fcClx, fcClx + lcbClx)

        val plcPcd = extractPlcPcd(clx) ?: return ""
        val rawText = decodePieces(plcPcd, wordDocument)
        return cleanupFieldCodesAndBreaks(rawText)
    }

    private fun looksLikeRtf(bytes: ByteArray): Boolean {
        val prefix = "{\\rtf"
        if (bytes.size < prefix.length) return false
        return prefix.indices.all { bytes[it].toInt().toChar() == prefix[it] }
    }

    /** Clx := zero or more Prc blocks (clxt=1, skipped) followed by exactly one Pcdt (clxt=2) wrapping the PlcPcd. */
    private fun extractPlcPcd(clx: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(clx).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos < clx.size && clx[pos].toInt() == 1) {
            val cbGrpprl = buf.getShort(pos + 1).toInt() and 0xFFFF
            pos += 1 + 2 + cbGrpprl
        }
        if (pos >= clx.size || clx[pos].toInt() != 2) return null
        pos += 1
        val lcbPlcPcd = buf.getInt(pos)
        pos += 4
        if (lcbPlcPcd <= 0 || pos + lcbPlcPcd > clx.size) return null
        return clx.copyOfRange(pos, pos + lcbPlcPcd)
    }

    private fun decodePieces(plcPcd: ByteArray, wordDocument: ByteArray): String {
        val buf = ByteBuffer.wrap(plcPcd).order(ByteOrder.LITTLE_ENDIAN)
        val n = (plcPcd.size - 4) / 12 // n+1 four-byte CPs, plus n eight-byte Pcd entries
        if (n <= 0) return ""
        val cps = IntArray(n + 1) { i -> buf.getInt(i * 4) }
        val pcdStart = 4 * (n + 1)

        val out = StringBuilder()
        for (i in 0 until n) {
            val cpStart = cps[i]
            val cpEnd = cps[i + 1]
            val nChars = cpEnd - cpStart
            if (nChars <= 0) continue
            val fcRaw = buf.getInt(pcdStart + i * 8 + 2)
            val compressed = (fcRaw and 0x40000000) != 0
            var fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                fc /= 2
                if (fc < 0 || fc + nChars > wordDocument.size) continue
                out.append(String(wordDocument, fc, nChars, WINDOWS_1252))
            } else {
                if (fc < 0 || fc + nChars * 2 > wordDocument.size) continue
                out.append(String(wordDocument, fc, nChars * 2, Charsets.UTF_16LE))
            }
        }
        return out.toString()
    }

    /**
     * Word stores paragraph/line/cell marks as literal control characters in the text itself,
     * and field codes (page numbers, TOC entries, hyperlinks) as a 0x13 instruction-begin /
     * 0x14 separator / 0x15 end marker triplet -- the instruction half (between 0x13 and 0x14)
     * is dropped, only the visible result (after 0x14) is kept.
     */
    private fun cleanupFieldCodesAndBreaks(text: String): String {
        val out = StringBuilder(text.length)
        var skipping = false
        for (c in text) {
            val code = c.code
            when (code) {
                FIELD_BEGIN_CODE -> skipping = true
                FIELD_SEPARATOR_CODE -> skipping = false
                FIELD_END_CODE -> {}
                CELL_MARK_CODE, LINE_BREAK_CODE, PAGE_BREAK_CODE -> if (!skipping) out.append('\n')
                else -> if (!skipping) {
                    if (c == '\r') out.append('\n')
                    else if (code >= 0x20 || c == '\t' || c == '\n') out.append(c)
                }
            }
        }
        return out.toString()
    }
}
