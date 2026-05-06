package com.damarquez.putz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.damarquez.putz.oauth.OAuthManager
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.AppNavGraph
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import com.damarquez.putz.ui.theme.PutzTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var oAuthManager: OAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Handle OAuth redirect if app was launched via deep link
        intent?.data?.let { oAuthManager.handleRedirect(it) }
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

    // Called when the app is already running and the OAuth redirect brings it to foreground
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { oAuthManager.handleRedirect(it) }
    }
}
