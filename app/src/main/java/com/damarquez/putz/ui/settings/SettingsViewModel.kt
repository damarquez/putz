package com.damarquez.putz.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.repository.LanFilesRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    fun showErrorMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun getApplicationContext() = getApplication<Application>()
}
