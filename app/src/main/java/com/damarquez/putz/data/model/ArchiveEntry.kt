package com.damarquez.putz.data.model

data class ArchiveEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
)

sealed class ArchiveSource {
    data class Local(val uri: String) : ArchiveSource()
    data class Lan(val connectionId: Long, val path: String) : ArchiveSource()
    data class Putio(val fileId: Long, val downloadUrl: String, val fileSize: Long) : ArchiveSource()
    // CONTRACT: stub convention — synced put.io file; serve from LAN mirror using the original file ID
    // localPath is the relative path from the stub JSON, passed to the daemon to avoid an index lookup
    data class Mirror(val putioFileId: Long, val localPath: String?) : ArchiveSource()
}

sealed class ArchiveDestination {
    data class Local(val treeUri: String) : ArchiveDestination()
    data class Lan(val connectionId: Long, val basePath: String) : ArchiveDestination()
    data class Putio(val parentFolderId: Long) : ArchiveDestination()
}

sealed class ExtractionProgress {
    data object Working : ExtractionProgress()
    data class Done(val count: Int) : ExtractionProgress()
    data class Error(val message: String) : ExtractionProgress()
}
