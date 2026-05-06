package com.damarquez.putz.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.repository.FilesRepository
import com.damarquez.putz.oauth.OAuthManager
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object AwaitingOAuth : AuthUiState()
    data object Loading : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    data object Success : AuthUiState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val filesRepository: FilesRepository,
    private val settingsRepository: SettingsRepository,
    private val oAuthManager: OAuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val savedClientId = settingsRepository.oauthClientIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch {
            oAuthManager.pendingUri
                .filterNotNull()
                .collect { uri ->
                    oAuthManager.clearPending()
                    val token = oAuthManager.parseToken(uri)
                    if (token != null) {
                        validateAndSaveToken(token)
                    } else {
                        _uiState.value = AuthUiState.Error(
                            "Authorization failed — no token in redirect URI.\n" +
                                "Received: $uri"
                        )
                    }
                }
        }
    }

    fun saveClientId(clientId: String) {
        viewModelScope.launch { settingsRepository.saveOauthClientId(clientId) }
    }

    fun buildOAuthUrl(clientId: String): String = oAuthManager.buildAuthUrl(clientId)

    fun onOAuthLaunched() {
        _uiState.value = AuthUiState.AwaitingOAuth
    }

    fun validateAndSaveToken(token: String) {
        if (token.isBlank()) {
            _uiState.value = AuthUiState.Error("Token cannot be empty")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = filesRepository.getAccountInfo(token.trim())) {
                is NetworkResult.Success -> {
                    settingsRepository.saveAuthToken(token.trim())  // synchronous write to EncryptedSharedPreferences
                    _uiState.value = AuthUiState.Success
                }
                is NetworkResult.Error -> {
                    val msg = when (result.code) {
                        401 -> "Invalid token — put.io rejected the authorization"
                        else -> result.message
                    }
                    _uiState.value = AuthUiState.Error(msg)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearError() {
        if (_uiState.value is AuthUiState.Error) _uiState.value = AuthUiState.Idle
    }

    fun cancelOAuth() {
        if (_uiState.value is AuthUiState.AwaitingOAuth) _uiState.value = AuthUiState.Idle
    }
}
