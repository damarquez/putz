package com.damarquez.putz.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.damarquez.putz.update.UpdateCheckResult
import com.damarquez.putz.update.UpdateSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val updateCheckResult by viewModel.updateCheckResult.collectAsState()
    val isCheckingForUpdate by viewModel.isCheckingForUpdate.collectAsState()
    val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            SettingsSectionHeader("App Update")
            ButtonRow(
                label = if (isCheckingForUpdate) "Checking..." else "Check for update",
                onClick = { if (!isCheckingForUpdate) viewModel.checkForUpdate() },
            )
        }
    }

    // CONTRACT: self-update — the check itself is cheap (no download); the APK is only
    // downloaded when "Install" is tapped, from whichever source (LAN daemon or Drive) the
    // check found it on. Tap-to-confirm only — Android's own install dialog is the final step.
    updateCheckResult?.let { result ->
        AlertDialog(
            onDismissRequest = viewModel::clearUpdateCheckResult,
            title = { Text("Update check") },
            text = {
                Text(
                    when (result) {
                        is UpdateCheckResult.UpdateAvailable -> {
                            val via = if (result.source == UpdateSource.LAN) "LAN" else "Drive"
                            if (isDownloadingUpdate) "Downloading update via $via..."
                            else "A newer build is available via $via."
                        }
                        UpdateCheckResult.UpToDate -> "Putz is up to date."
                        is UpdateCheckResult.Error -> "Couldn't check for updates: ${result.message}"
                    }
                )
            },
            confirmButton = {
                if (result is UpdateCheckResult.UpdateAvailable) {
                    TextButton(
                        enabled = !isDownloadingUpdate,
                        onClick = {
                            scope.launch {
                                val apkFile = viewModel.downloadUpdateApk()
                                viewModel.clearUpdateCheckResult()
                                if (apkFile != null) {
                                    if (viewModel.canRequestInstalls()) {
                                        context.startActivity(viewModel.buildInstallIntent(apkFile))
                                    } else {
                                        context.startActivity(viewModel.installSettingsIntent())
                                    }
                                }
                            }
                        },
                    ) { Text(if (isDownloadingUpdate) "Downloading..." else "Install") }
                } else {
                    TextButton(onClick = viewModel::clearUpdateCheckResult) { Text("OK") }
                }
            },
            dismissButton = {
                if (result is UpdateCheckResult.UpdateAvailable) {
                    TextButton(
                        enabled = !isDownloadingUpdate,
                        onClick = viewModel::clearUpdateCheckResult,
                    ) { Text("Later") }
                }
            },
        )
    }
}
