package com.damarquez.putz.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TrashFile
import com.damarquez.putz.data.repository.TrashRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TrashUiState {
    data object Loading : TrashUiState()
    data class Success(
        val files: List<TrashFile>,
        val trashSize: Long,
        val isRefreshing: Boolean = false,
    ) : TrashUiState()
    data class Error(val message: String) : TrashUiState()
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TrashUiState>(TrashUiState.Loading)
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        loadTrash()
    }

    fun loadTrash(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) {
                val current = _uiState.value
                if (current is TrashUiState.Success) {
                    _uiState.value = current.copy(isRefreshing = true)
                }
            } else {
                _uiState.value = TrashUiState.Loading
            }

            val token = settingsRepository.authTokenFlow.first()
            when (val result = trashRepository.listTrash(token)) {
                is NetworkResult.Success -> {
                    val (files, trashSize) = result.data
                    _uiState.value = TrashUiState.Success(
                        files = files.sortedBy { it.name.lowercase() },
                        trashSize = trashSize,
                    )
                }
                is NetworkResult.Error -> _uiState.value = TrashUiState.Error(result.message)
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun restoreFiles(files: List<TrashFile>) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = trashRepository.restoreTrash(token, files.map { it.id })) {
                is NetworkResult.Success -> {
                    _snackbarMessage.value = if (files.size == 1) "\"${files.first().name}\" restored" else "${files.size} items restored"
                    loadTrash(isRefresh = true)
                }
                is NetworkResult.Error -> _snackbarMessage.value = "Restore failed: ${result.message}"
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun deleteFiles(files: List<TrashFile>) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = trashRepository.deleteFromTrash(token, files.map { it.id })) {
                is NetworkResult.Success -> {
                    _snackbarMessage.value = if (files.size == 1) "Permanently deleted" else "${files.size} items permanently deleted"
                    loadTrash(isRefresh = true)
                }
                is NetworkResult.Error -> _snackbarMessage.value = "Delete failed: ${result.message}"
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = trashRepository.emptyTrash(token)) {
                is NetworkResult.Success -> {
                    _snackbarMessage.value = "Trash emptied"
                    loadTrash(isRefresh = true)
                }
                is NetworkResult.Error -> _snackbarMessage.value = "Empty trash failed: ${result.message}"
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
}
