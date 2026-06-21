package com.damarquez.putz.data.archive

import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipNativeInitializationException

/**
 * Initializes the 7-Zip-JBinding native library exactly once per process. Shared by
 * ArchiveRepository and CbrExtractor so they don't race each other initializing it independently.
 */
object SevenZipInit {
    val initialized: Boolean by lazy {
        try {
            SevenZip.initSevenZipFromPlatformJAR()
            true
        } catch (e: SevenZipNativeInitializationException) {
            android.util.Log.e("SevenZipInit", "7-Zip init failed: ${e.message}")
            false
        }
    }
}
