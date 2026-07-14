package com.damarquez.putz.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.repository.LanFilesRepository
import com.damarquez.putz.data.transport.LanDaemonTransport
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.files.LanFolderPickerState
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import com.damarquez.putz.update.AppUpdateManager
import com.damarquez.putz.update.UpdateCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val lanFilesRepository: LanFilesRepository,
    private val lanDaemonTransport: LanDaemonTransport,
    private val appUpdateManager: AppUpdateManager,
) : AndroidViewModel(application) {

    val appCategory = settingsRepository.appCategoryFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppCategory.NORMAL)

    val appMode = settingsRepository.appModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppMode.SYSTEM)

    val googleAccount = settingsRepository.googleTokenFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val googleWebClientId = settingsRepository.googleWebClientIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val putioLocalLanConnectionId = settingsRepository.putioLocalLanConnectionIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val putioLocalLanPath = settingsRepository.putioLocalLanPathFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val plexLibraryLanConnectionId = settingsRepository.plexLibraryLanConnectionIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val plexLibraryLanPath = settingsRepository.plexLibraryLanPathFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val plexampLibraryLanConnectionId = settingsRepository.plexampLibraryLanConnectionIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val plexampLibraryLanPath = settingsRepository.plexampLibraryLanPathFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val lanConnections = lanFilesRepository.getConnections()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val daemonLanEnabled = settingsRepository.lanEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val daemonLanHost = settingsRepository.lanHostFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val daemonLanPort = settingsRepository.lanPortFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 9090)
    val daemonLanApiKey = settingsRepository.lanApiKeyFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _daemonLanHost = MutableStateFlow("")
    val daemonLanHostEdit: StateFlow<String> = _daemonLanHost.asStateFlow()
    private val _daemonLanPort = MutableStateFlow("9090")
    val daemonLanPortEdit: StateFlow<String> = _daemonLanPort.asStateFlow()
    private val _daemonLanApiKey = MutableStateFlow("")
    val daemonLanApiKeyEdit: StateFlow<String> = _daemonLanApiKey.asStateFlow()
    private val _daemonLanReachable = MutableStateFlow<Boolean?>(null)
    val daemonLanReachable: StateFlow<Boolean?> = _daemonLanReachable.asStateFlow()

    private val _jackettBaseUrl = MutableStateFlow("")
    val jackettBaseUrlEdit: StateFlow<String> = _jackettBaseUrl.asStateFlow()
    private val _jackettApiKey = MutableStateFlow("")
    val jackettApiKeyEdit: StateFlow<String> = _jackettApiKey.asStateFlow()

    init {
        viewModelScope.launch {
            _daemonLanHost.value = settingsRepository.lanHostFlow.first()
            _daemonLanPort.value = settingsRepository.lanPortFlow.first().toString()
            _daemonLanApiKey.value = settingsRepository.lanApiKeyFlow.first()
            _jackettBaseUrl.value = settingsRepository.jackettBaseUrlFlow.first()
            _jackettApiKey.value = settingsRepository.jackettApiKeyFlow.first()
        }
    }

    fun onJackettBaseUrlChange(v: String) { _jackettBaseUrl.value = v }
    fun onJackettApiKeyChange(v: String) { _jackettApiKey.value = v }

    fun saveJackettSettings() {
        viewModelScope.launch {
            settingsRepository.saveJackettBaseUrl(_jackettBaseUrl.value)
            settingsRepository.saveJackettApiKey(_jackettApiKey.value)
            _snackbarMessage.value = "Jackett settings saved"
        }
    }

    fun setDaemonLanEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.saveLanEnabled(enabled) }
    }
    fun onDaemonLanHostChange(v: String) { _daemonLanHost.value = v }
    fun onDaemonLanPortChange(v: String) { _daemonLanPort.value = v.filter { it.isDigit() }.take(5) }
    fun onDaemonLanApiKeyChange(v: String) { _daemonLanApiKey.value = v }

    fun saveDaemonLanSettings() {
        viewModelScope.launch {
            settingsRepository.saveLanHost(_daemonLanHost.value)
            val port = _daemonLanPort.value.toIntOrNull() ?: 9090
            settingsRepository.saveLanPort(port)
            _daemonLanPort.value = port.toString()
            settingsRepository.saveLanApiKey(_daemonLanApiKey.value)
            _snackbarMessage.value = "Daemon LAN settings saved"
        }
    }

    fun checkDaemonLanReachability() {
        viewModelScope.launch {
            _daemonLanReachable.value = null
            _daemonLanReachable.value = lanDaemonTransport.isReachable()
        }
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // CONTRACT: self-update — manual "Check for update" trigger only; no background polling.
    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateCheckResult: StateFlow<UpdateCheckResult?> = _updateCheckResult.asStateFlow()
    private val _isCheckingForUpdate = MutableStateFlow(false)
    val isCheckingForUpdate: StateFlow<Boolean> = _isCheckingForUpdate.asStateFlow()

    fun checkForUpdate() {
        val account = googleAccount.value
        if (account.isBlank()) {
            _snackbarMessage.value = "Sign in with Google to check for updates"
            return
        }
        viewModelScope.launch {
            _isCheckingForUpdate.value = true
            _updateCheckResult.value = appUpdateManager.checkForUpdate(account)
            _isCheckingForUpdate.value = false
        }
    }

    fun clearUpdateCheckResult() {
        _updateCheckResult.value = null
    }

    fun canRequestInstalls(): Boolean = appUpdateManager.canRequestInstalls()
    fun installSettingsIntent() = appUpdateManager.installSettingsIntent()
    fun buildInstallIntent(apkFile: java.io.File) = appUpdateManager.buildInstallIntent(apkFile)

    fun setAppCategory(category: AppCategory) {
        viewModelScope.launch { settingsRepository.saveAppCategory(category) }
    }

    fun setAppMode(mode: AppMode) {
        viewModelScope.launch { settingsRepository.saveAppMode(mode) }
    }

    fun setGoogleAccount(account: String) {
        if (account.isBlank()) {
            settingsRepository.clearGoogleToken()
            _snackbarMessage.value = "Google account removed"
        } else {
            settingsRepository.saveGoogleToken(account)
            _snackbarMessage.value = "Signed in as $account"
        }
    }

    fun setGoogleWebClientId(clientId: String) {
        viewModelScope.launch { settingsRepository.saveGoogleWebClientId(clientId) }
    }

    fun setPutioLocalLanConnection(connectionId: Long?) {
        viewModelScope.launch { settingsRepository.savePutioLocalLanConnectionId(connectionId) }
    }

    fun setPutioLocalLanPath(path: String) {
        viewModelScope.launch { settingsRepository.savePutioLocalLanPath(path) }
    }

    fun setPlexLibraryLanConnection(connectionId: Long?) {
        viewModelScope.launch { settingsRepository.savePlexLibraryLanConnectionId(connectionId) }
    }

    fun setPlexLibraryLanPath(path: String) {
        viewModelScope.launch { settingsRepository.savePlexLibraryLanPath(path) }
    }

    fun setPlexampLibraryLanConnection(connectionId: Long?) {
        viewModelScope.launch { settingsRepository.savePlexampLibraryLanConnectionId(connectionId) }
    }

    fun setPlexampLibraryLanPath(path: String) {
        viewModelScope.launch { settingsRepository.savePlexampLibraryLanPath(path) }
    }

    private val _plexRootPickerState = MutableStateFlow<LanFolderPickerState?>(null)
    val plexRootPickerState: StateFlow<LanFolderPickerState?> = _plexRootPickerState.asStateFlow()

    fun openPlexRootPicker() {
        val connectionId = plexLibraryLanConnectionId.value ?: return
        val initialState = LanFolderPickerState(
            connectionId = connectionId,
            rootPath = "",
            currentPath = "",
            isLoading = true,
        )
        _plexRootPickerState.value = initialState
        viewModelScope.launch { loadPlexRootFolders(connectionId, "") }
    }

    fun browsePlexRootFolder(folder: com.damarquez.putz.data.model.PutioFile) {
        val current = _plexRootPickerState.value ?: return
        val newPath = if (current.currentPath.isEmpty()) folder.name
            else "${current.currentPath}/${folder.name}"
        _plexRootPickerState.value = current.copy(
            pathStack = current.pathStack + current.currentPath,
            currentPath = newPath,
            folders = emptyList(),
            isLoading = true,
            error = null,
        )
        viewModelScope.launch { loadPlexRootFolders(current.connectionId, newPath) }
    }

    fun plexRootPickerNavigateUp() {
        val current = _plexRootPickerState.value ?: return
        if (!current.canNavigateUp) return
        val previousPath = current.pathStack.last()
        _plexRootPickerState.value = current.copy(
            pathStack = current.pathStack.dropLast(1),
            currentPath = previousPath,
            folders = emptyList(),
            isLoading = true,
            error = null,
        )
        viewModelScope.launch { loadPlexRootFolders(current.connectionId, previousPath) }
    }

    fun dismissPlexRootPicker() {
        _plexRootPickerState.value = null
    }

    fun selectPlexRootPath(path: String) {
        _plexRootPickerState.value = null
        viewModelScope.launch { settingsRepository.savePlexLibraryLanPath(path) }
    }

    private suspend fun loadPlexRootFolders(connectionId: Long, path: String) {
        try {
            val files = lanFilesRepository.listDirectory(connectionId, path).last()
            val folders = files.filter { it.isFolder }
            val current = _plexRootPickerState.value ?: return
            _plexRootPickerState.value = current.copy(
                folders = folders,
                isLoading = false,
                error = null,
            )
        } catch (e: Exception) {
            val current = _plexRootPickerState.value ?: return
            _plexRootPickerState.value = current.copy(isLoading = false, error = e.message ?: "Failed to load folders")
        }
    }

    private val _plexampRootPickerState = MutableStateFlow<LanFolderPickerState?>(null)
    val plexampRootPickerState: StateFlow<LanFolderPickerState?> = _plexampRootPickerState.asStateFlow()

    fun openPlexampRootPicker() {
        val connectionId = plexampLibraryLanConnectionId.value ?: return
        val initialState = LanFolderPickerState(
            connectionId = connectionId,
            rootPath = "",
            currentPath = "",
            isLoading = true,
        )
        _plexampRootPickerState.value = initialState
        viewModelScope.launch { loadPlexampRootFolders(connectionId, "") }
    }

    fun browsePlexampRootFolder(folder: com.damarquez.putz.data.model.PutioFile) {
        val current = _plexampRootPickerState.value ?: return
        val newPath = if (current.currentPath.isEmpty()) folder.name
            else "${current.currentPath}/${folder.name}"
        _plexampRootPickerState.value = current.copy(
            pathStack = current.pathStack + current.currentPath,
            currentPath = newPath,
            folders = emptyList(),
            isLoading = true,
            error = null,
        )
        viewModelScope.launch { loadPlexampRootFolders(current.connectionId, newPath) }
    }

    fun plexampRootPickerNavigateUp() {
        val current = _plexampRootPickerState.value ?: return
        if (!current.canNavigateUp) return
        val previousPath = current.pathStack.last()
        _plexampRootPickerState.value = current.copy(
            pathStack = current.pathStack.dropLast(1),
            currentPath = previousPath,
            folders = emptyList(),
            isLoading = true,
            error = null,
        )
        viewModelScope.launch { loadPlexampRootFolders(current.connectionId, previousPath) }
    }

    fun dismissPlexampRootPicker() {
        _plexampRootPickerState.value = null
    }

    fun selectPlexampRootPath(path: String) {
        _plexampRootPickerState.value = null
        viewModelScope.launch { settingsRepository.savePlexampLibraryLanPath(path) }
    }

    private suspend fun loadPlexampRootFolders(connectionId: Long, path: String) {
        try {
            val files = lanFilesRepository.listDirectory(connectionId, path).last()
            val folders = files.filter { it.isFolder }
            val current = _plexampRootPickerState.value ?: return
            _plexampRootPickerState.value = current.copy(
                folders = folders,
                isLoading = false,
                error = null,
            )
        } catch (e: Exception) {
            val current = _plexampRootPickerState.value ?: return
            _plexampRootPickerState.value = current.copy(isLoading = false, error = e.message ?: "Failed to load folders")
        }
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    fun showErrorMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun getApplicationContext() = getApplication<Application>()
}
