package com.damarquez.putz.ui.chain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// CONTRACT: CHAIN
@HiltViewModel
class ChainViewModel @Inject constructor(
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val chainedTransfers: StateFlow<List<CalibreTransferEntity>> = calibreRepository.observeChainedTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isPlacing = MutableStateFlow(false)
    val isPlacing: StateFlow<Boolean> = _isPlacing.asStateFlow()

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    fun reorder(orderedFileIds: List<Long>) {
        viewModelScope.launch { calibreRepository.reorderChain(orderedFileIds) }
    }

    fun removeFromChain(fileId: Long) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) return@launch
            val result = calibreRepository.removeFromChain(fileId, account)
            _snackbarMessage.value = when (result) {
                is NetworkResult.Success -> "Sent"
                is NetworkResult.Error -> "Could not send: ${result.message}"
                else -> "Could not send"
            }
        }
    }

    fun placeChain(onPlaced: () -> Unit) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isBlank()) {
                _snackbarMessage.value = "Not signed in"
                return@launch
            }
            _isPlacing.value = true
            val result = calibreRepository.placeChain(account)
            _isPlacing.value = false
            when (result) {
                is NetworkResult.Success -> {
                    _snackbarMessage.value = "Chain placed"
                    onPlaced()
                }
                is NetworkResult.Error -> _snackbarMessage.value = "Could not place chain: ${result.message}"
                else -> _snackbarMessage.value = "Could not place chain"
            }
        }
    }
}
