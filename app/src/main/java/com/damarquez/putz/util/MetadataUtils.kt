package com.damarquez.putz.util

object MetadataUtils {
    private val EBOOK_EXTENSIONS = setOf("epub", "mobi", "pdf", "azw3", "fb2", "cbz", "cbr")

    fun isEbook(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in EBOOK_EXTENSIONS
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
        
        return Pair(nameWithoutExt, "Unknown")
    }
}
