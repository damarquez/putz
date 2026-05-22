package com.damarquez.putz.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.repository.LanFilesRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.files.LanFolderPickerState
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val lanFilesRepository: LanFilesRepository,
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

    val lanConnections = lanFilesRepository.getConnections()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

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
            val files = lanFilesRepository.listDirectory(connectionId, path).first()
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

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    fun showErrorMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun getApplicationContext() = getApplication<Application>()
}
