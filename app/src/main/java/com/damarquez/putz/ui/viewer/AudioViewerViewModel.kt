package com.damarquez.putz.ui.viewer

import androidx.lifecycle.ViewModel
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Resolves any extra HTTP headers a given audio [filePath] needs. Only the LAN mirror endpoint
 *  (sidekick daemon) requires one — the `X-Sidekick-Key` it checks on every request — so this
 *  stays out of the shared ViewerEvent/nav-route plumbing rather than threading a secret through it. */
@HiltViewModel
class AudioViewerViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    suspend fun headersFor(filePath: String): Map<String, String> {
        val host = settingsRepository.lanHostFlow.first().trim()
        val port = settingsRepository.lanPortFlow.first()
        if (host.isBlank() || !filePath.startsWith("http://$host:$port/")) return emptyMap()
        val apiKey = settingsRepository.lanApiKeyFlow.first()
        return if (apiKey.isNotBlank()) mapOf("X-Sidekick-Key" to apiKey) else emptyMap()
    }
}
