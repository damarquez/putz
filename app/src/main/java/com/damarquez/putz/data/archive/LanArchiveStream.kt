package com.damarquez.putz.data.archive

import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import net.sf.sevenzipjbinding.IInStream

class LanArchiveStream(
    private val smbFile: com.hierynomus.smbj.share.File,
    private val share: DiskShare,
    private val session: Session,
    private val connection: Connection,
    private val fileSize: Long,
) : IInStream {

    private var position: Long = 0L

    override fun read(data: ByteArray): Int {
        if (data.isEmpty() || position >= fileSize) return 0
        val toRead = minOf(data.size.toLong(), fileSize - position).toInt()
        val n = smbFile.read(data, position, 0, toRead)
        if (n > 0) position += n
        return if (n <= 0) 0 else n
    }

    override fun seek(offset: Long, seekOrigin: Int): Long {
        position = when (seekOrigin) {
            0 -> offset                    // SEEK_SET
            1 -> position + offset         // SEEK_CUR
            2 -> fileSize + offset         // SEEK_END
            else -> position
        }.coerceIn(0L, fileSize)
        return position
    }

    override fun close() {
        runCatching { smbFile.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
    }
}
