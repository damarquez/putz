package com.damarquez.putz.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import com.damarquez.putz.update.UpdateCheckResult
import com.damarquez.putz.update.UpdateSource
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onNavigateToCalibreTransfers: () -> Unit,
    onNavigateToLanConnections: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val appCategory by viewModel.appCategory.collectAsState()
    val appMode by viewModel.appMode.collectAsState()
    val googleAccount by viewModel.googleAccount.collectAsState()
    val googleWebClientId by viewModel.googleWebClientId.collectAsState()
    val putioLocalLanConnectionId by viewModel.putioLocalLanConnectionId.collectAsState()
    val putioLocalLanPath by viewModel.putioLocalLanPath.collectAsState()
    val plexLibraryLanConnectionId by viewModel.plexLibraryLanConnectionId.collectAsState()
    val plexLibraryLanPath by viewModel.plexLibraryLanPath.collectAsState()
    val plexampLibraryLanConnectionId by viewModel.plexampLibraryLanConnectionId.collectAsState()
    val plexampLibraryLanPath by viewModel.plexampLibraryLanPath.collectAsState()
    val lanConnections by viewModel.lanConnections.collectAsState()
    val updateCheckResult by viewModel.updateCheckResult.collectAsState()
    val isCheckingForUpdate by viewModel.isCheckingForUpdate.collectAsState()
    val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLanConnectionPicker by remember { mutableStateOf(false) }
    var editingLanPath by remember { mutableStateOf<String?>(null) }
    var showPlexLanConnectionPicker by remember { mutableStateOf(false) }
    var showPlexampLanConnectionPicker by remember { mutableStateOf(false) }
    val plexRootPickerState by viewModel.plexRootPickerState.collectAsState()
    val plexampRootPickerState by viewModel.plexampRootPickerState.collectAsState()
    val daemonLanEnabled by viewModel.daemonLanEnabled.collectAsState()
    val daemonLanHostEdit by viewModel.daemonLanHostEdit.collectAsState()
    val daemonLanPortEdit by viewModel.daemonLanPortEdit.collectAsState()
    val daemonLanApiKeyEdit by viewModel.daemonLanApiKeyEdit.collectAsState()
    val daemonLanReachable by viewModel.daemonLanReachable.collectAsState()
    val jackettBaseUrlEdit by viewModel.jackettBaseUrlEdit.collectAsState()
    val jackettApiKeyEdit by viewModel.jackettApiKeyEdit.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showGoogleSignOutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onSnackbarShown()
        }
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
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            ButtonRow(
                label = "Manage Calibre Transfers",
                onClick = onNavigateToCalibreTransfers
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("LAN Files")
            ButtonRow(
                label = "Manage LAN Connections",
                onClick = onNavigateToLanConnections
            )

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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("put.io Local Sync")
            val selectedConn = lanConnections.find { it.id == putioLocalLanConnectionId }
            ButtonRow(
                label = if (selectedConn != null) "LAN connection: ${selectedConn.label}" else "Select LAN connection…",
                onClick = { showLanConnectionPicker = true }
            )
            if (selectedConn != null) {
                val lanPathState = editingLanPath ?: putioLocalLanPath
                OutlinedTextField(
                    value = lanPathState,
                    onValueChange = { editingLanPath = it },
                    label = { Text("Path within share") },
                    placeholder = { Text(".put.io") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    supportingText = { Text("Subfolder inside the share where files are stored") },
                )
                if (editingLanPath != null) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = {
                            viewModel.setPutioLocalLanPath(editingLanPath ?: "")
                            editingLanPath = null
                        }) { Text("Save") }
                        TextButton(onClick = { editingLanPath = null }) { Text("Cancel") }
                    }
                }
                ButtonRow(
                    label = "Remove put.io local sync",
                    onClick = {
                        viewModel.setPutioLocalLanConnection(null)
                        viewModel.setPutioLocalLanPath("")
                        editingLanPath = null
                    },
                    isError = true
                )
            }

            if (showLanConnectionPicker) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showLanConnectionPicker = false },
                    title = { Text("Select LAN connection") },
                    text = {
                        Column {
                            if (lanConnections.isEmpty()) {
                                Text("No LAN connections configured. Add one in LAN Files settings first.")
                            } else {
                                lanConnections.forEach { conn ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.setPutioLocalLanConnection(conn.id)
                                                showLanConnectionPicker = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = conn.id == putioLocalLanConnectionId,
                                            onClick = {
                                                viewModel.setPutioLocalLanConnection(conn.id)
                                                showLanConnectionPicker = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(conn.label, style = MaterialTheme.typography.bodyLarge)
                                            Text("\\\\${conn.host}\\${conn.shareName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanConnectionPicker = false }) { Text("Cancel") }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Plex Library")
            val selectedPlexConn = lanConnections.find { it.id == plexLibraryLanConnectionId }
            ButtonRow(
                label = if (selectedPlexConn != null) "LAN connection: ${selectedPlexConn.label}" else "Select LAN connection…",
                onClick = { showPlexLanConnectionPicker = true }
            )
            if (selectedPlexConn != null) {
                ButtonRow(
                    label = if (plexLibraryLanPath.isNotBlank()) "Library root: /${plexLibraryLanPath}" else "Browse for library root…",
                    onClick = { viewModel.openPlexRootPicker() }
                )
                ButtonRow(
                    label = "Remove Plex library",
                    onClick = {
                        viewModel.setPlexLibraryLanConnection(null)
                        viewModel.setPlexLibraryLanPath("")
                    },
                    isError = true
                )
            }

            if (showPlexLanConnectionPicker) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showPlexLanConnectionPicker = false },
                    title = { Text("Select LAN connection for Plex") },
                    text = {
                        Column {
                            if (lanConnections.isEmpty()) {
                                Text("No LAN connections configured. Add one in LAN Files settings first.")
                            } else {
                                lanConnections.forEach { conn ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.setPlexLibraryLanConnection(conn.id)
                                                showPlexLanConnectionPicker = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = conn.id == plexLibraryLanConnectionId,
                                            onClick = {
                                                viewModel.setPlexLibraryLanConnection(conn.id)
                                                showPlexLanConnectionPicker = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(conn.label, style = MaterialTheme.typography.bodyLarge)
                                            Text("\\\\${conn.host}\\${conn.shareName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showPlexLanConnectionPicker = false }) { Text("Cancel") }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Plexamp Library")
            val selectedPlexampConn = lanConnections.find { it.id == plexampLibraryLanConnectionId }
            ButtonRow(
                label = if (selectedPlexampConn != null) "LAN connection: ${selectedPlexampConn.label}" else "Select LAN connection…",
                onClick = { showPlexampLanConnectionPicker = true }
            )
            if (selectedPlexampConn != null) {
                ButtonRow(
                    label = if (plexampLibraryLanPath.isNotBlank()) "Library root: /${plexampLibraryLanPath}" else "Browse for library root…",
                    onClick = { viewModel.openPlexampRootPicker() }
                )
                ButtonRow(
                    label = "Remove Plexamp library",
                    onClick = {
                        viewModel.setPlexampLibraryLanConnection(null)
                        viewModel.setPlexampLibraryLanPath("")
                    },
                    isError = true
                )
            }

            if (showPlexampLanConnectionPicker) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showPlexampLanConnectionPicker = false },
                    title = { Text("Select LAN connection for Plexamp") },
                    text = {
                        Column {
                            if (lanConnections.isEmpty()) {
                                Text("No LAN connections configured. Add one in LAN Files settings first.")
                            } else {
                                lanConnections.forEach { conn ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.setPlexampLibraryLanConnection(conn.id)
                                                showPlexampLanConnectionPicker = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = conn.id == plexampLibraryLanConnectionId,
                                            onClick = {
                                                viewModel.setPlexampLibraryLanConnection(conn.id)
                                                showPlexampLanConnectionPicker = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(conn.label, style = MaterialTheme.typography.bodyLarge)
                                            Text("\\\\${conn.host}\\${conn.shareName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showPlexampLanConnectionPicker = false }) { Text("Cancel") }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsSectionHeader("Sidekick Daemon (Tailscale)")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
                        true  -> Text("Reachable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        false -> Text("Unreachable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        null  -> Unit
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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

    plexRootPickerState?.let { pickerState ->
        com.damarquez.putz.ui.files.PlexFolderPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.dismissPlexRootPicker() },
            onNavigateUp = { viewModel.plexRootPickerNavigateUp() },
            onNavigateInto = { folder -> viewModel.browsePlexRootFolder(folder) },
            onSelect = { path -> viewModel.selectPlexRootPath(path) },
        )
    }

    plexampRootPickerState?.let { pickerState ->
        com.damarquez.putz.ui.files.PlexFolderPickerSheet(
            state = pickerState,
            onDismiss = { viewModel.dismissPlexampRootPicker() },
            onNavigateUp = { viewModel.plexampRootPickerNavigateUp() },
            onNavigateInto = { folder -> viewModel.browsePlexampRootFolder(folder) },
            onSelect = { path -> viewModel.selectPlexampRootPath(path) },
        )
    }
}

@Composable
private fun ButtonRow(
    label: String,
    onClick: () -> Unit,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun RadioRow(
    label: String,
    description: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
