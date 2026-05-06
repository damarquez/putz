package com.damarquez.putz.util

import android.net.Uri

object MagnetParser {

    /** Extracts the human-readable display name from the dn= parameter. */
    fun extractDisplayName(magnetUri: String): String? = runCatching {
        // Try Android Uri first (handles percent-encoding automatically)
        val fromUri = Uri.parse(magnetUri).getQueryParameter("dn")
            ?.replace("+", " ")?.trim()?.takeIf { it.isNotBlank() }
        if (fromUri != null) return@runCatching fromUri

        // Regex fallback for magnet: URIs that Uri treats as opaque
        Regex("[?&]dn=([^&]+)").find(magnetUri)
            ?.groupValues?.get(1)
            ?.let { Uri.decode(it) }
            ?.replace("+", " ")?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Extracts the info-hash (lowercase hex or base32) from the xt=urn:btih: parameter. */
    fun extractInfoHash(magnetUri: String): String? = runCatching {
        Regex("[?&]xt=urn:btih:([^&]+)", RegexOption.IGNORE_CASE)
            .find(magnetUri)
            ?.groupValues?.get(1)
            ?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun isMagnetLink(uri: String): Boolean = uri.trimStart().startsWith("magnet:", ignoreCase = true)
}
