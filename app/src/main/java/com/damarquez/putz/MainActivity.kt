package com.damarquez.putz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                AppNavGraph(settingsRepository = settingsRepository)
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
            uri.scheme == "putz" -> oAuthManager.handleRedirect(uri)
            MagnetParser.isMagnetLink(uri.toString()) -> pendingMagnetRepository.set(uri.toString())
        }
    }
}
