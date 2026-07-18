package com.damarquez.putz.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val appCategory by viewModel.appCategory.collectAsState()
    val appMode by viewModel.appMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SettingsSectionHeader("Appearance Type")
            RadioRow(
                label = "Normal",
                description = "Standard colors and animations",
                selected = appCategory == AppCategory.NORMAL,
                onClick = { viewModel.setAppCategory(AppCategory.NORMAL) }
            )
            RadioRow(
                label = "E-Ink",
                description = "High contrast, no animations",
                selected = appCategory == AppCategory.EINK,
                onClick = { viewModel.setAppCategory(AppCategory.EINK) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Theme Mode")
            RadioRow(
                label = "Light",
                selected = appMode == AppMode.LIGHT,
                onClick = { viewModel.setAppMode(AppMode.LIGHT) }
            )
            RadioRow(
                label = "Dark",
                selected = appMode == AppMode.DARK,
                onClick = { viewModel.setAppMode(AppMode.DARK) }
            )
            RadioRow(
                label = "System",
                selected = appMode == AppMode.SYSTEM,
                onClick = { viewModel.setAppMode(AppMode.SYSTEM) }
            )
        }
    }
}
