package com.damarquez.putz.util

import android.os.ParcelFileDescriptor
import com.damarquez.putz.data.archive.LocalArchiveStream
import com.damarquez.putz.data.archive.SevenZipInit
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import java.io.File
import java.io.OutputStream

/** Extracts a CBR's image pages, in archive order, for the comic preview viewer. */
object CbrExtractor {

    private data class ImageEntry(val index: Int, val path: String, val name: String)

    fun extractImages(cbrFile: File, destDir: File): List<File> {
        check(SevenZipInit.initialized) { "7-Zip native library failed to initialize" }
        destDir.mkdirs()
        val pfd = ParcelFileDescriptor.open(cbrFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val stream = LocalArchiveStream(pfd)
        return try {
            val inArchive = SevenZip.openInArchive(null, stream)
            try {
                val entries = (0 until inArchive.numberOfItems).mapNotNull { i ->
                    runCatching {
                        val path = (inArchive.getProperty(i, PropID.PATH) as? String)?.replace('\\', '/')
                            ?: return@mapNotNull null
                        val isDir = (inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean) ?: false
                        val name = path.substringAfterLast('/')
                        if (isDir || !MetadataUtils.isImage(name)) null else ImageEntry(i, path, name)
                    }.getOrNull()
                }.sortedBy { it.path }

                if (entries.isEmpty()) return emptyList()

                val outFiles = entries.mapIndexed { order, entry ->
                    val ext = entry.name.substringAfterLast('.', "img")
                    File(destDir, "page_%03d.%s".format(order, ext))
                }
                val indexToOutFile = entries.mapIndexed { order, entry -> entry.index to outFiles[order] }.toMap()
                val indices = entries.map { it.index }.toIntArray()

                inArchive.extract(indices, false, object : IArchiveExtractCallback {
                    private var currentOs: OutputStream? = null

                    override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                        if (extractAskMode != ExtractAskMode.EXTRACT) return null
                        val outFile = indexToOutFile[index] ?: return null
                        val os = outFile.outputStream()
                        currentOs = os
                        return ISequentialOutStream { data -> os.write(data); data.size }
                    }

                    override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                    override fun setOperationResult(result: ExtractOperationResult) {
                        runCatching { currentOs?.close() }
                        currentOs = null
                    }

                    override fun setTotal(total: Long) {}
                    override fun setCompleted(complete: Long) {}
                })

                outFiles.filter { it.exists() && it.length() > 0 }
            } finally {
                runCatching { inArchive.close() }
            }
        } finally {
            runCatching { stream.close() }
        }
    }
}
