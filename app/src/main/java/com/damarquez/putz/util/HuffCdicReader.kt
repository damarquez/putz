package com.damarquez.putz.util

import java.io.ByteArrayOutputStream

private val HUFF_MAGIC = byteArrayOf('H'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0x18)
private val CDIC_MAGIC = byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 0, 0, 0, 0x10)

/**
 * Mobipocket HUFF/CDIC text decompression: a canonical Huffman code over a CDIC phrase
 * dictionary (used instead of plain PalmDOC LZ77 by some MOBI/AZW3 files, normally reserved
 * for dictionaries but occasionally used for body text too). Ported from the `mobi` PyPI
 * package's `HuffcdicReader` (mobi_uncompress.py), which follows the long-established
 * community-reverse-engineered MOBI HUFF/CDIC record layout.
 */
internal class HuffCdicReader {
    // dict1[topByte] = (codeLength, isTerminalAtThisLength, maxCodeForThisLength)
    private lateinit var dict1: Array<Triple<Int, Boolean, Long>>
    private lateinit var minCode: LongArray // index 0..32
    private lateinit var maxCode: LongArray // index 0..32
    private val dictionary = ArrayList<Pair<ByteArray, Boolean>?>()

    fun loadHuff(huff: ByteArray) {
        require(startsWith(huff, HUFF_MAGIC)) { "invalid HUFF record" }
        val off1 = readUInt32(huff, 8).toInt()
        val off2 = readUInt32(huff, 12).toInt()

        dict1 = Array(256) { i ->
            val v = readUInt32(huff, off1 + i * 4)
            val codeLen = (v and 0x1FL).toInt()
            val terminal = (v and 0x80L) != 0L
            val rawMaxCode = v ushr 8
            val maxc = ((rawMaxCode + 1) shl (32 - codeLen)) - 1
            Triple(codeLen, terminal, maxc)
        }

        val dict2 = LongArray(64) { i -> readUInt32(huff, off2 + i * 4) }
        minCode = LongArray(33)
        maxCode = LongArray(33)
        for (codeLen in 1..32) {
            minCode[codeLen] = dict2[(codeLen - 1) * 2] shl (32 - codeLen)
            val rawMax = dict2[(codeLen - 1) * 2 + 1]
            maxCode[codeLen] = ((rawMax + 1) shl (32 - codeLen)) - 1
        }
        dictionary.clear()
    }

    fun loadCdic(cdic: ByteArray) {
        require(startsWith(cdic, CDIC_MAGIC)) { "invalid CDIC record" }
        val phrases = readUInt32(cdic, 8).toInt()
        val bits = readUInt32(cdic, 12).toInt()
        val n = minOf(1 shl bits, phrases - dictionary.size)
        for (i in 0 until n) {
            val off = readUInt16(cdic, 16 + i * 2)
            val blen = readUInt16(cdic, 16 + off)
            val length = blen and 0x7FFF
            val start = 18 + off
            val slice = cdic.copyOfRange(start, start + length)
            val alreadyPlain = (blen and 0x8000) != 0
            dictionary.add(slice to alreadyPlain)
        }
    }

    fun unpack(data: ByteArray): ByteArray {
        val padded = data + ByteArray(8)
        var bitsLeft = data.size * 8
        var pos = 0
        var x = readUInt64(padded, pos)
        var n = 32
        val out = ByteArrayOutputStream(data.size * 2)

        while (true) {
            if (n <= 0) {
                pos += 4
                x = readUInt64(padded, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL

            val (initialCodeLen, terminal, initialMaxCode) = dict1[((code ushr 24) and 0xFFL).toInt()]
            var codeLen = initialCodeLen
            var maxc = initialMaxCode
            if (!terminal) {
                while (code < minCode[codeLen]) codeLen++
                maxc = maxCode[codeLen]
            }

            n -= codeLen
            bitsLeft -= codeLen
            if (bitsLeft < 0) break

            val r = ((maxc - code) ushr (32 - codeLen)).toInt()
            val entry = dictionary[r] ?: throw IllegalStateException("invalid HUFF/CDIC dictionary reference")
            val (rawSlice, alreadyPlain) = entry
            val slice = if (alreadyPlain) {
                rawSlice
            } else {
                dictionary[r] = null
                val decoded = unpack(rawSlice)
                dictionary[r] = decoded to true
                decoded
            }
            out.write(slice)
        }
        return out.toByteArray()
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 4) result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        return result
    }

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        return result
    }
}
