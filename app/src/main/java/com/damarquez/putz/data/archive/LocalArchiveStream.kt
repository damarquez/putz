package com.damarquez.putz.data.archive

import android.os.ParcelFileDescriptor
import net.sf.sevenzipjbinding.IInStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class LocalArchiveStream(private val pfd: ParcelFileDescriptor) : IInStream {

    private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel

    override fun read(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val buf = ByteBuffer.wrap(data)
        val n = channel.read(buf)
        return if (n <= 0) 0 else n
    }

    override fun seek(offset: Long, seekOrigin: Int): Long {
        val newPos = when (seekOrigin) {
            0 -> offset                          // SEEK_SET
            1 -> channel.position() + offset     // SEEK_CUR
            2 -> channel.size() + offset         // SEEK_END
            else -> channel.position()
        }
        channel.position(newPos.coerceAtLeast(0))
        return channel.position()
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }
}
