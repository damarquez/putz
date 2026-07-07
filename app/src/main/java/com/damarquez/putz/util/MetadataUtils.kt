package com.damarquez.putz.util

// CONTRACT: stub convention, Putz file state — all isX(fileName) callers must pass file.displayName, not file.name
object MetadataUtils {
    private val EBOOK_EXTENSIONS = setOf("epub", "mobi", "pdf", "azw3", "azw", "fb2", "cbz", "cbr", "rar", "zip", "7z", "prc", "rtf", "doc", "docx", "odt", "txt", "htm", "html", "djvu")
    private val ARCHIVE_EXTENSIONS = setOf("rar", "zip", "7z")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "flac")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif")
    private val MULTI_TRACK_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "flac")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "m4v", "ts", "webm", "flv", "mpg", "mpeg", "divx")
    private val PLEXAMP_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "flac", "aac", "wav", "ogg", "opus", "wma", "ape", "alac", "aiff", "dsf")

    /** Extra single-word junk tags (beyond format/extension names) to strip from parsed titles. Extend as needed. */
    private val JUNK_PAREN_WORDS = setOf("retail")

    private fun cleanStubSuffix(fileName: String): String {
        return fileName.removeSuffix(".sk_synced").removeSuffix(".sk_sync")
    }

    /** Strips `.sk_synced[.id]` / `.sk_sync` suffixes from a raw file name for display. */
    fun stripStubExtension(fileName: String): String =
        if (".sk_synced" in fileName) fileName.substringBefore(".sk_synced")
        else fileName.removeSuffix(".sk_sync")

    fun <T> sortByName(files: List<T>, caseSensitive: Boolean, name: (T) -> String): List<T> =
        if (caseSensitive) files.sortedBy(name) else files.sortedBy { name(it).lowercase() }

    /**
     * Collapses the leading portion of [name] shared with [previousName] into "..." when that
     * shared prefix is longer than 5 characters, so long near-duplicate filenames in a reordered
     * list stay legible. Returns [name] unchanged when there's no previous item or the shared
     * prefix is too short to bother collapsing.
     */
    fun collapsePrefix(name: String, previousName: String?): String {
        if (previousName == null) return name
        val sharedLength = name.commonPrefixWith(previousName).length
        return if (sharedLength > 5) "..." + name.substring(sharedLength) else name
    }

    /**
     * Length of the leading portion of [name] that will be collapsed to "..." on [nextName]
     * (the row below it), or 0 if [nextName] won't collapse against [name]. Used to underline
     * the part of an uncollapsed name that the next row's "..." stands in for.
     */
    fun collapsedPrefixLength(name: String, nextName: String?): Int {
        if (nextName == null) return 0
        val sharedLength = name.commonPrefixWith(nextName).length
        return if (sharedLength > 5) sharedLength else 0
    }

    fun isImage(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    fun isArchive(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext in ARCHIVE_EXTENSIONS
    }

    /** True for CBR/CBZ comic archives that can be unpacked into a chaptered PDF/CBZ. */
    fun isComicArchive(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "cbr" || ext == "cbz"
    }

    fun isEbook(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext in EBOOK_EXTENSIONS || ext in AUDIO_EXTENSIONS
    }

    /** True for formats that can be combined into an M4B pack. */
    fun isMultiTrackAudio(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext in MULTI_TRACK_AUDIO_EXTENSIONS
    }

    /** True for PDF files that can be joined into a fused PDF. */
    fun isPdf(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "pdf"
    }

    /** True for EPUB files that can be joined into a fused EPUB. */
    fun isEpub(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "epub"
    }

    fun isMobi(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "mobi"
    }

    fun isTxt(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "txt"
    }

    fun isHtml(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "htm" || ext == "html"
    }

    /** AZW3 (Kindle KF8) — same PalmDB/PalmDOC text records as legacy MOBI, just with a KF8 layout system on top. */
    fun isAzw3(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "azw3"
    }

    /** AZW — pre-KF8 Kindle format; identical Mobipocket PalmDB container to .mobi, just renamed by Amazon. */
    fun isAzw(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "azw"
    }

    fun isRtf(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "rtf"
    }

    fun isDocx(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "docx"
    }

    fun isDoc(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext == "doc"
    }

    fun isVideo(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    fun isAudio(fileName: String): Boolean {
        val ext = cleanStubSuffix(fileName).substringAfterLast('.', "").lowercase()
        return ext in PLEXAMP_AUDIO_EXTENSIONS
    }

    /** Parse movie title and year from a video filename (e.g. "The.Matrix.1999.mkv" → "The Matrix", "1999"). */
    fun parseMovieTitleAndYear(fileName: String): Pair<String, String> {
        val nameWithoutExt = cleanStubSuffix(fileName).substringBeforeLast('.')
        val cleaned = nameWithoutExt.replace('.', ' ').replace('_', ' ').trim()
        val yearMatch = Regex("""\b(19|20)\d{2}\b""").find(cleaned)
        return if (yearMatch != null) {
            val title = cleaned.substring(0, yearMatch.range.first).trim()
            Pair(title.ifBlank { cleaned }, yearMatch.value)
        } else {
            Pair(cleaned, "")
        }
    }

    fun extractMetadata(fileName: String): Pair<String, String> {
        val nameWithoutExt = cleanStubSuffix(fileName).substringBeforeLast('.')
        
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

    /**
     * Cleans up a parsed title: strips parenthetical groups that are just a format/extension tag
     * (e.g. "(epub)") or a known junk word (e.g. "(retail)"), then normalizes underscores/whitespace
     * and title-cases each word. Shared by the single-file and batch send-to-Calibre "fix" buttons.
     */
    fun fixTitleCapitalization(title: String): String {
        val junkTokens = EBOOK_EXTENSIONS + PLEXAMP_AUDIO_EXTENSIONS + JUNK_PAREN_WORDS
        val withoutJunkParens = Regex("""\(\s*([A-Za-z0-9]+)\s*\)""").replace(title) { match ->
            if (match.groupValues[1].lowercase() in junkTokens) "" else match.value
        }
        return withoutJunkParens
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
    }

    /**
     * Normalize a file/folder displayName for use as a web search query.
     * Removes the file extension (if it looks like one: 2-6 ASCII letters), then replaces every
     * run of non-alphanumeric characters with a single space and trims the result.
     *
     * Examples:
     *   "my.file/#2.by.this_author1,author2.epub" → "my file 2 by this author1 author2"
     *   "The.Matrix.1999"                         → "The Matrix 1999"
     *   "My Folder"                               → "My Folder"
     */
    fun webSearchQuery(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "")
        val withoutExt = if (ext.isNotEmpty() && ext.length in 2..6 && ext.all { it.isLetter() }) {
            displayName.substringBeforeLast('.')
        } else {
            displayName
        }
        return withoutExt.replace(Regex("[^A-Za-z0-9]+"), " ").trim()
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
