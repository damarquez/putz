package com.damarquez.putz.oauth

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OAuthManager @Inject constructor() {

    private val _pendingUri = MutableStateFlow<Uri?>(null)
    val pendingUri = _pendingUri.asStateFlow()

    fun handleRedirect(uri: Uri) {
        _pendingUri.value = uri
    }

    fun clearPending() {
        _pendingUri.value = null
    }

    fun buildAuthUrl(clientId: String): String =
        "https://api.put.io/v2/oauth2/authenticate" +
            "?client_id=${Uri.encode(clientId)}" +
            "&response_type=token" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}"

    fun parseToken(uri: Uri): String? {
        // Token arrives in the URI fragment: putz://oauth#access_token=TOKEN&token_type=Bearer
        val fragment = uri.fragment ?: return null
        return fragment
            .split("&")
            .firstOrNull { it.startsWith("access_token=") }
            ?.removePrefix("access_token=")
    }

    companion object {
        const val REDIRECT_URI = "putz://oauth"
        const val SCHEME = "putz"
        const val HOST = "oauth"
    }
}
