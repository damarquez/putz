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
}

sealed class ArchiveDestination {
    data class Local(val treeUri: String) : ArchiveDestination()
    data class Lan(val connectionId: Long, val basePath: String) : ArchiveDestination()
}

sealed class ExtractionProgress {
    data object Working : ExtractionProgress()
    data class Done(val count: Int) : ExtractionProgress()
    data class Error(val message: String) : ExtractionProgress()
}
