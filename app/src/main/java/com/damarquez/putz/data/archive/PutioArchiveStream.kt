package com.damarquez.putz.data.archive

import net.sf.sevenzipjbinding.IInStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val CHUNK_SIZE = 256 * 1024L // 256 KB

class PutioArchiveStream(
    private val downloadUrl: String,
    private val fileSize: Long,
    private val okHttpClient: OkHttpClient,
) : IInStream {

    private var position: Long = 0L
    private val buffer = ByteArray(CHUNK_SIZE.toInt())
    private var bufferOffset: Long = -1L
    private var bufferFilled: Int = 0

    override fun read(data: ByteArray): Int {
        if (data.isEmpty() || position >= fileSize) return 0

        if (position < bufferOffset || position >= bufferOffset + bufferFilled) {
            fetchChunk(position)
        }

        val bufferPos = (position - bufferOffset).toInt()
        val available = bufferFilled - bufferPos
        val toRead = minOf(data.size, available)
        buffer.copyInto(data, 0, bufferPos, bufferPos + toRead)
        position += toRead
        return toRead
    }

    override fun seek(offset: Long, seekOrigin: Int): Long {
        position = when (seekOrigin) {
            0 -> offset
            1 -> position + offset
            2 -> fileSize + offset
            else -> position
        }.coerceIn(0L, fileSize)
        return position
    }

    override fun close() {}

    private fun fetchChunk(from: Long) {
        val rangeStart = from
        val rangeEnd = minOf(from + CHUNK_SIZE - 1, fileSize - 1)
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Range", "bytes=$rangeStart-$rangeEnd")
            .build()
        val response = okHttpClient.newCall(request).execute()
        if (response.code != 206 && response.code != 200) {
            response.close()
            throw IOException("Range request failed: HTTP ${response.code}")
        }
        val bytes = response.use { it.body?.bytes() }
            ?: throw IOException("Empty response body for range $rangeStart-$rangeEnd")
        bytes.copyInto(buffer, 0, 0, bytes.size)
        bufferOffset = rangeStart
        bufferFilled = bytes.size
    }
}
