package com.damarquez.putz.util

object MetadataUtils {
    private val EBOOK_EXTENSIONS = setOf("epub", "mobi", "pdf", "azw3", "fb2", "cbz", "cbr", "rar", "zip", "prc")
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

    fun sanitizeHtml(text: String, htmlText: String? = null): String {
        val source = htmlText ?: text
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return ""
        
        return try {
            // Use fromHtml to strip unwanted tags and styles
            val spanned = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(trimmed, android.text.Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(trimmed)
            }

            // Convert back to HTML to get the "clean" version
            val cleanHtml = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.text.Html.toHtml(spanned, android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.toHtml(spanned)
            }

            // Cleanup some of the junk toHtml adds (like <html><body>...</body></html>)
            var result = cleanHtml
                .replace("<html>", "")
                .replace("</html>", "")
                .replace("<body>", "")
                .replace("</body>", "")
                .replace("<p dir=\"ltr\">", "<p>")
                .replace(" dir=\"ltr\"", "") // Remove any remaining dir attributes
                .trim()

            // Html.toHtml() encodes non-ASCII characters as numeric entities (e.g. ü → &#252;).
            // Decode them back to their Unicode characters so the text stays readable.
            result = result.replace(Regex("&#(\\d+);")) { mr ->
                mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
            }

            result
        } catch (e: Exception) {
            // Fallback for non-Android environments or errors
            if (!trimmed.contains("<") || !trimmed.contains(">")) {
                trimmed.replace("\n", "<br/>")
            } else {
                trimmed
            }
        }
    }
}
