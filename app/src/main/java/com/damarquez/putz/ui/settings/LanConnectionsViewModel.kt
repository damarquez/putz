package com.damarquez.putz.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.local.LanConnectionEntity
import com.damarquez.putz.data.repository.LanFilesRepository
import com.damarquez.putz.security.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TestConnectionState {
    data object Idle : TestConnectionState()
    data object Testing : TestConnectionState()
    data object Success : TestConnectionState()
    data class Failed(val message: String) : TestConnectionState()
}

@HiltViewModel
class LanConnectionsViewModel @Inject constructor(
    private val lanFilesRepository: LanFilesRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val connections: StateFlow<List<LanConnectionEntity>> = lanFilesRepository.getConnections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _testState = MutableStateFlow<TestConnectionState>(TestConnectionState.Idle)
    val testState: StateFlow<TestConnectionState> = _testState.asStateFlow()

    fun getPassword(connectionId: Long): String = secureStorage.getLanPassword(connectionId)

    fun addConnection(label: String, host: String, username: String, password: String, shareName: String) {
        viewModelScope.launch {
            val error = lanFilesRepository.addConnection(label, host, username, password, shareName)
            _snackbarMessage.value = error ?: "Connection \"$label\" added"
        }
    }

    fun updateConnection(id: Long, label: String, host: String, username: String, password: String, shareName: String) {
        viewModelScope.launch {
            val error = lanFilesRepository.updateConnection(id, label, host, username, password, shareName)
            _snackbarMessage.value = error ?: "Connection updated"
        }
    }

    fun deleteConnection(id: Long) {
        viewModelScope.launch {
            lanFilesRepository.deleteConnection(id)
            _snackbarMessage.value = "Connection deleted"
        }
    }

    fun testConnection(host: String, username: String, password: String, shareName: String) {
        viewModelScope.launch {
            _testState.value = TestConnectionState.Testing
            val error = lanFilesRepository.testConnection(host, username, password, shareName)
            _testState.value = if (error == null) TestConnectionState.Success else TestConnectionState.Failed(error)
        }
    }

    fun resetTestState() {
        _testState.value = TestConnectionState.Idle
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }
}
