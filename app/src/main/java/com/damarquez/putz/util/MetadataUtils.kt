package com.damarquez.putz.util

object MetadataUtils {
    private val EBOOK_EXTENSIONS = setOf("epub", "mobi", "pdf", "azw3", "fb2", "cbz", "cbr", "rar", "zip")
    private val ARCHIVE_EXTENSIONS = setOf("rar", "zip")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a")
    private val MULTI_TRACK_AUDIO_EXTENSIONS = setOf("mp3", "m4a")

    fun isArchive(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in ARCHIVE_EXTENSIONS
    }

    fun isEbook(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in EBOOK_EXTENSIONS || ext in AUDIO_EXTENSIONS
    }

    /** True for formats that can be combined into an M4B pack (mp3, m4a). */
    fun isMultiTrackAudio(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in MULTI_TRACK_AUDIO_EXTENSIONS
    }

    fun extractMetadata(fileName: String): Pair<String, String> {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        
        // Pattern: Author - Title
        val dashParts = nameWithoutExt.split(" - ")
        if (dashParts.size >= 2) {
            return Pair(dashParts[1].trim(), dashParts[0].trim())
        }
        
        // Pattern: Title (Author)
        val parenRegex = """^(.*)\s*\((.*)\)$""".toRegex()
        val match = parenRegex.find(nameWithoutExt)
        if (match != null) {
            return Pair(match.groupValues[1].trim(), match.groupValues[2].trim())
        }
        
        // Pattern: Title by Author
        val byIndex = nameWithoutExt.lastIndexOf(" by ", ignoreCase = true)
        if (byIndex != -1) {
            val title = nameWithoutExt.substring(0, byIndex).trim()
            val author = nameWithoutExt.substring(byIndex + 4).trim()
            if (title.isNotEmpty() && author.isNotEmpty()) {
                return Pair(title, author)
            }
        }

        return Pair(nameWithoutExt, "Unknown")
    }
}
