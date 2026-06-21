package com.damarquez.putz.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

private val CFB_SIGNATURE = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
)
private const val FREESECT = -1 // 0xFFFFFFFF
private const val ENDOFCHAIN = -2 // 0xFFFFFFFE

/**
 * Minimal reader for the OLE2/CFB (Compound File Binary) container format used by legacy
 * .doc/.xls/.ppt files: parses the FAT sector chain, MiniFAT, and directory tree just enough
 * to locate and read a named stream's full bytes. Verified byte-for-byte against the `olefile`
 * Python reference for both regular-FAT- and MiniFAT-backed streams before porting.
 */
class CfbReader(private val data: ByteArray) {
    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    private val sectorShift = buf.getShort(0x1E).toInt()
    private val sectorSize = 1 shl sectorShift
    private val miniSectorShift = buf.getShort(0x20).toInt()
    private val miniSectorSize = 1 shl miniSectorShift
    private val numFatSectors = buf.getInt(0x2C)
    private val dirStart = buf.getInt(0x30)
    private val miniStreamCutoff = buf.getInt(0x38)
    private val miniFatStart = buf.getInt(0x3C)
    private val numMiniFatSectors = buf.getInt(0x40)
    private val difatStart = buf.getInt(0x44)
    private val numDifatSectors = buf.getInt(0x48)

    private val fat: IntArray
    private val minifat: IntArray
    private val miniStreamData: ByteArray
    private val dirEntries: List<DirEntry>

    private data class DirEntry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        require(data.size >= 8 && (0 until 8).all { data[it] == CFB_SIGNATURE[it] }) { "not a CFB/OLE2 file" }

        val difat = ArrayList<Int>(109)
        for (i in 0 until 109) difat.add(buf.getInt(0x4C + i * 4))
        var difatSect = difatStart
        var difatSectorsLeft = numDifatSectors
        while (difatSect != ENDOFCHAIN && difatSect != FREESECT && difatSectorsLeft > 0) {
            val entries = readSectorInts(difatSect)
            difat.addAll(entries.dropLast(1))
            difatSect = entries.last()
            difatSectorsLeft--
        }

        val fatList = ArrayList<Int>(numFatSectors * (sectorSize / 4))
        var fatSectorsUsed = 0
        for (fatSect in difat) {
            if (fatSect == FREESECT || fatSect == ENDOFCHAIN || fatSectorsUsed >= numFatSectors) continue
            fatList.addAll(readSectorInts(fatSect).toList())
            fatSectorsUsed++
        }
        fat = fatList.toIntArray()

        // Mini stream lives inside the root directory entry's regular-FAT-backed stream.
        val rawDir = readChainRaw(dirStart, fat)
        dirEntries = parseDirEntries(rawDir)
        val root = dirEntries.firstOrNull { it.type == 5 }
        miniStreamData = if (root != null) readChain(root.start, root.size.toInt()) else ByteArray(0)

        val minifatList = ArrayList<Int>(numMiniFatSectors * (sectorSize / 4))
        var miniSect = miniFatStart
        var miniSectorsLeft = numMiniFatSectors
        while (miniSect != ENDOFCHAIN && miniSect != FREESECT && miniSectorsLeft > 0) {
            minifatList.addAll(readSectorInts(miniSect).toList())
            miniSect = fat.getOrElse(miniSect) { ENDOFCHAIN }
            miniSectorsLeft--
        }
        minifat = minifatList.toIntArray()
    }

    fun openStream(name: String): ByteArray? {
        val entry = dirEntries.firstOrNull { it.name.equals(name, ignoreCase = true) && (it.type == 2 || it.type == 5) }
            ?: return null
        return if (entry.size < miniStreamCutoff && entry.type != 5) {
            readMiniChain(entry.start, entry.size.toInt())
        } else {
            readChain(entry.start, entry.size.toInt())
        }
    }

    private fun sector(index: Int): ByteArray {
        val offset = 512 + index * sectorSize
        return data.copyOfRange(offset, minOf(offset + sectorSize, data.size))
    }

    private fun miniSector(index: Int): ByteArray {
        val offset = index * miniSectorSize
        return miniStreamData.copyOfRange(offset, minOf(offset + miniSectorSize, miniStreamData.size))
    }

    private fun readSectorInts(index: Int): IntArray {
        val chunk = sector(index)
        val n = sectorSize / 4
        val out = IntArray(n)
        val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = if (i * 4 + 4 <= chunk.size) bb.getInt(i * 4) else FREESECT
        return out
    }

    private fun readChain(startSect: Int, size: Int): ByteArray = readChainRaw(startSect, fat).copyOf(size)

    private fun readChainRaw(startSect: Int, fatTable: IntArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sect = startSect
        var guard = 0
        while (sect != ENDOFCHAIN && sect != FREESECT && guard < fatTable.size + 1) {
            out.write(sector(sect))
            sect = fatTable.getOrElse(sect) { ENDOFCHAIN }
            guard++
        }
        return out.toByteArray()
    }

    private fun readMiniChain(startSect: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sect = startSect
        var guard = 0
        while (sect != ENDOFCHAIN && sect != FREESECT && guard < minifat.size + 1) {
            out.write(miniSector(sect))
            sect = minifat.getOrElse(sect) { ENDOFCHAIN }
            guard++
        }
        return out.toByteArray().copyOf(size)
    }

    private fun parseDirEntries(raw: ByteArray): List<DirEntry> {
        val entries = ArrayList<DirEntry>()
        // ByteBuffer.wrap(array, offset, length)'s absolute getX(index) calls address the whole
        // backing array, not relative to `offset` — so this must wrap the full array once and
        // use i-relative absolute offsets per entry, not re-wrap a sub-range per entry.
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 128 <= raw.size) {
            val nameLenBytes = bb.getShort(i + 64).toInt() and 0xFFFF
            if (nameLenBytes >= 2) {
                val name = String(raw, i, nameLenBytes - 2, Charsets.UTF_16LE)
                val type = raw[i + 66].toInt() and 0xFF
                val start = bb.getInt(i + 116)
                val size = bb.getLong(i + 120)
                entries.add(DirEntry(name, type, start, size))
            }
            i += 128
        }
        return entries
    }
}
