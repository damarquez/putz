package com.damarquez.putz.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.damarquez.putz.ui.components.StorageBar
import com.damarquez.putz.ui.components.formatDiskSize
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAccountScreen(
    onNavigateUp: () -> Unit,
    onSignOutOfPutio: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val accountInfo by viewModel.accountInfo.collectAsState()
    val googleAccount by viewModel.googleAccount.collectAsState()
    val googleWebClientId by viewModel.googleWebClientId.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showGoogleSignOutConfirm by remember { mutableStateOf(false) }
    var showPutioSignOutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
    }

    if (showPutioSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showPutioSignOutConfirm = false },
            title = { Text("Sign out") },
            text = { Text("Are you sure you want to sign out of Putz?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPutioSignOutConfirm = false
                        viewModel.signOutOfPutio()
                        onSignOutOfPutio()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPutioSignOutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showGoogleSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showGoogleSignOutConfirm = false },
            title = { Text("Sign out of Google") },
            text = { Text("Calibre integration features will be disabled until you sign in again. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGoogleSignOutConfirm = false
                        viewModel.setGoogleAccount("")
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoogleSignOutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val gso = remember(googleWebClientId) {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_METADATA_READONLY))

        if (googleWebClientId.isNotBlank()) {
            builder.requestIdToken(googleWebClientId)
        }
        builder.build()
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            account?.email?.let { viewModel.setGoogleAccount(it) }
        } catch (e: Exception) {
            viewModel.showErrorMessage("Google Sign-In failed: ${e.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SettingsSectionHeader("put.io Account")
            accountInfo?.let { info ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(text = info.username, style = MaterialTheme.typography.bodyMedium)
                    if (!info.mail.isNullOrBlank()) {
                        Text(
                            text = info.mail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val subtitle = when {
                        info.diskQuota > 0 -> "${formatDiskSize(info.diskAvail)} of ${formatDiskSize(info.diskQuota)} free"
                        info.diskUsed > 0 -> "${formatDiskSize(info.diskUsed)} used, quota unknown"
                        else -> null
                    }
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    StorageBar(
                        usedPercent = info.diskUsedPercent,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            ButtonRow(
                label = "Sign out",
                onClick = { showPutioSignOutConfirm = true },
                isError = true,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Google Account")
            if (googleAccount.isBlank()) {
                ButtonRow(
                    label = "Sign in with Google",
                    onClick = {
                        val client = GoogleSignIn.getClient(viewModel.getApplicationContext(), gso)
                        googleSignInLauncher.launch(client.signInIntent)
                    }
                )
            } else {
                Text(
                    text = "Signed in as: $googleAccount",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                ButtonRow(
                    label = "Sign out",
                    onClick = { showGoogleSignOutConfirm = true },
                    isError = true
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Google Configuration")
            OutlinedTextField(
                value = googleWebClientId,
                onValueChange = { viewModel.setGoogleWebClientId(it) },
                label = { Text("Web Client ID") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("12345...apps.googleusercontent.com") }
            )
        }
    }
}
