package com.damarquez.putz.util

/**
 * Early warning from a torrent's name alone: will this video need converting before the
 * household's Plex clients (Fire TV, Samsung TV, phones) can Direct Play it?
 *
 * The NAS running Plex can't convert video fast enough to watch (0.22x realtime for an SD Xvid
 * AVI on 2026-10-11), so a file that needs video conversion is effectively unwatchable until the
 * daemon's video converter has processed it. This is only a hint from release-name tags; the
 * daemon ffprobes the real file at Send to Plex time (CONTRACT: SEND_TO_PLEX playback check,
 * CONTRACTS.md §48) and the video converter decides from the actual streams.
 */
object VideoPlaybackHint {

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "wmv", "asf", "flv", "mov", "mpg", "mpeg", "vob", "rm",
        "rmvb", "divx", "webm", "iso", "ts", "m2ts",
    )
    private val NON_VIDEO_EXTENSIONS = setOf(
        "epub", "pdf", "mobi", "azw3", "cbz", "cbr", "mp3", "flac", "m4a", "m4b", "aac", "ogg",
        "zip", "rar", "7z", "exe", "txt", "doc", "docx", "jpg", "png",
    )

    // Release-name tags that mark a video even without a file extension.
    private val VIDEO_TAGS = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|x264|x265|h\.?264|h\.?265|hevc|avc|xvid|divx|bluray|blu-ray|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|dvdscr|hdrip|remux|s\d{1,2}e\d{1,3})\b""",
    )

    private data class Marker(val regex: Regex, val label: String)

    private val PROBLEMS = listOf(
        Marker(Regex("""(?i)\b(xvid|divx)\b"""), "XviD/DivX video"),
        Marker(Regex("""(?i)\bwmv\d?\b|\.wmv$|\.asf$"""), "WMV video"),
        Marker(Regex("""(?i)\bav1\b"""), "AV1 video"),
        Marker(Regex("""(?i)\b(hi10p?|10-?bit)\b.*\b(x264|h\.?264|avc)\b|\b(x264|h\.?264|avc)\b.*\b(hi10p?|10-?bit)\b"""), "10-bit H.264"),
        Marker(Regex("""(?i)\b(mpeg-?2|vc-?1)\b"""), "MPEG-2/VC-1 video"),
        Marker(Regex("""(?i)\.(flv|rm|rmvb|divx)$"""), "old video format"),
        Marker(Regex("""(?i)\.avi$|\bavi\b"""), "AVI container"),
        Marker(Regex("""(?i)\.(iso|vob)$|\b(dvd[59]|dvdr|iso)\b"""), "DVD image"),
    )

    /** "Likely needs conversion: …" or null when nothing in the name suggests a problem. */
    fun hint(name: String): String? {
        val trimmed = name.trim().removeSuffix(".sk_synced")
        val ext = trimmed.substringAfterLast('.', "").lowercase()
        if (ext in NON_VIDEO_EXTENSIONS) return null
        if (ext !in VIDEO_EXTENSIONS && !VIDEO_TAGS.containsMatchIn(trimmed)) return null
        val problems = PROBLEMS.filter { it.regex.containsMatchIn(trimmed) }.map { it.label }
            .distinct()
        if (problems.isEmpty()) return null
        return "Likely needs conversion for Plex: ${problems.joinToString(", ")}"
    }
}
