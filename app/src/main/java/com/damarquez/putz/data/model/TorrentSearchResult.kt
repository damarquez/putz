package com.damarquez.putz.data.model

/** Media type for the Cinemeta/Torrentio (Stremio) pipeline. */
enum class MediaType(val stremioType: String) {
    MOVIE("movie"),
    SERIES("series"),
}

/** A title candidate returned by the Cinemeta metadata catalog. */
data class MetaCandidate(
    val imdbId: String,
    val name: String,
    val releaseInfo: String? = null,
    val poster: String? = null,
    val type: MediaType,
)

/** A unified torrent search result, regardless of engine (Torrentio or Jackett). */
data class TorrentSearchResult(
    val title: String,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    /** Magnet URI (or other URL accepted by put.io transfers/add). */
    val magnet: String,
    val infoHash: String? = null,
    /** Tracker / provider label shown in the UI, e.g. "ThePirateBay (Torrentio)". */
    val source: String,
)
