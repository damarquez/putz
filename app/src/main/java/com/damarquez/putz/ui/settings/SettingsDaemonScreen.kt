package com.damarquez.putz.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDaemonScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val daemonLanEnabled by viewModel.daemonLanEnabled.collectAsState()
    val daemonLanHostEdit by viewModel.daemonLanHostEdit.collectAsState()
    val daemonLanPortEdit by viewModel.daemonLanPortEdit.collectAsState()
    val daemonLanApiKeyEdit by viewModel.daemonLanApiKeyEdit.collectAsState()
    val daemonLanReachable by viewModel.daemonLanReachable.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sidekick Daemon") },
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
            SettingsSectionHeader("Sidekick Daemon (Tailscale)")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Use direct LAN connection", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = daemonLanEnabled, onCheckedChange = viewModel::setDaemonLanEnabled)
            }
            if (daemonLanEnabled) {
                OutlinedTextField(
                    value = daemonLanHostEdit,
                    onValueChange = viewModel::onDaemonLanHostChange,
                    label = { Text("Host (Tailscale IP or hostname)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = daemonLanPortEdit,
                    onValueChange = viewModel::onDaemonLanPortChange,
                    label = { Text("Port (default 9090)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = daemonLanApiKeyEdit,
                    onValueChange = viewModel::onDaemonLanApiKeyChange,
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::saveDaemonLanSettings) { Text("Save") }
                    TextButton(onClick = viewModel::checkDaemonLanReachability) { Text("Check") }
                    when (daemonLanReachable) {
                        true -> Text("Reachable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        false -> Text("Unreachable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        null -> Unit
                    }
                }
            }
        }
    }
}
