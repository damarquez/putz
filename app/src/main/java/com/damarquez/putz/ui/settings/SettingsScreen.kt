package com.damarquez.putz.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

/** Top-level Settings: just a list of categories, each pushing its own focused screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToLibraries: () -> Unit,
    onNavigateToDaemon: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            ButtonRow(label = "Appearance", onClick = onNavigateToAppearance)
            HorizontalDivider()
            ButtonRow(label = "Account", onClick = onNavigateToAccount)
            HorizontalDivider()
            ButtonRow(label = "Libraries & Sync", onClick = onNavigateToLibraries)
            HorizontalDivider()
            ButtonRow(label = "Sidekick Daemon", onClick = onNavigateToDaemon)
            HorizontalDivider()
            ButtonRow(label = "Torrent Search", onClick = onNavigateToSearch)
            HorizontalDivider()
            ButtonRow(label = "About", onClick = onNavigateToAbout)
        }
    }
}
