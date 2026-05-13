package com.damarquez.putz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.oauth.OAuthManager
import com.damarquez.putz.oauth.PendingMagnetRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.AppNavGraph
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import com.damarquez.putz.ui.theme.PutzTheme
import com.damarquez.putz.util.MagnetParser
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var oAuthManager: OAuthManager
    @Inject lateinit var pendingMagnetRepository: PendingMagnetRepository
    @Inject lateinit var pendingCoverRepository: PendingCoverRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val appCategory by settingsRepository.appCategoryFlow
                .collectAsState(initial = AppCategory.NORMAL)
            val appMode by settingsRepository.appModeFlow
                .collectAsState(initial = AppMode.SYSTEM)

            PutzTheme(category = appCategory, mode = appMode) {
                AppNavGraph(
                    settingsRepository = settingsRepository,
                    pendingCoverRepository = pendingCoverRepository
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        when {
            uri.scheme == "putz" && uri.host == "oauth" -> oAuthManager.handleRedirect(uri)
            uri.scheme == "putz" && uri.host == "replace_cover" -> {
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null) pendingCoverRepository.set(uuid)
            }
            MagnetParser.isMagnetLink(uri.toString()) -> pendingMagnetRepository.set(uri.toString())
        }
    }
}
