package com.damarquez.putz.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val jackettBaseUrlEdit by viewModel.jackettBaseUrlEdit.collectAsState()
    val jackettApiKeyEdit by viewModel.jackettApiKeyEdit.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Torrent Search") },
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
            SettingsSectionHeader("Torrent Search (Jackett)")
            Text(
                text = "Used by the Find Content tab to search non-video content. Point it at your self-hosted Jackett instance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = jackettBaseUrlEdit,
                onValueChange = viewModel::onJackettBaseUrlChange,
                label = { Text("Jackett URL") },
                placeholder = { Text("http://192.168.1.10:9117") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = jackettApiKeyEdit,
                onValueChange = viewModel::onJackettApiKeyChange,
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextButton(onClick = viewModel::saveJackettSettings) { Text("Save") }
            }
        }
    }
}
