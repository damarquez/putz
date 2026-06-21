package com.damarquez.putz.util

import java.io.File

/**
 * Detects books encrypted by the calibre/sidekick "protected" transfer pipeline
 * (calibre_assets/book_encryptor.py — same magic header as calibreanywhere's
 * BookEncryptionHandler). Putz has no decrypt capability (no password input), so previews
 * just need to recognize this case and say so instead of failing with a generic error.
 */
object EncryptedFileSignature {
    private val MAGIC = byteArrayOf(
        0xCA.toByte(), 0x45, 0x4E, 0x43, 0x00, 0x01, 0x00, 0x00,
    )

    const val MESSAGE = "This file is protected and can't be previewed here — open it in CalibreAnywhere."

    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        return file.inputStream().use { stream ->
            val header = ByteArray(MAGIC.size)
            stream.read(header) == MAGIC.size && header.contentEquals(MAGIC)
        }
    }
}
