package com.damarquez.putz.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.model.TransferGroup
import com.damarquez.putz.data.model.group
import com.damarquez.putz.data.model.isActive
import com.damarquez.putz.data.repository.TransfersRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TransfersUiState {
    data object Loading : TransfersUiState()
    data class Success(
        val grouped: Map<TransferGroup, List<PutioTransfer>>,
        val isRefreshing: Boolean = false,
    ) : TransfersUiState()
    data class Error(val message: String) : TransfersUiState()
}

@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val transfersRepository: TransfersRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TransfersUiState>(TransfersUiState.Loading)
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        startPolling()
    }

    private fun startPolling(immediate: Boolean = true) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            if (!immediate) delay(1_000)
            while (isActive) {
                val isRefresh = _uiState.value is TransfersUiState.Success
                if (isRefresh) {
                    val current = _uiState.value as TransfersUiState.Success
                    _uiState.value = current.copy(isRefreshing = true)
                }

                val token = settingsRepository.authTokenFlow.first()
                when (val result = transfersRepository.listTransfers(token)) {
                    is NetworkResult.Success -> {
                        val transfers = result.data
                        val grouped = buildGroupedMap(transfers)
                        _uiState.value = TransfersUiState.Success(grouped = grouped)

                        // Poll faster when transfers are active
                        val hasActive = transfers.any { it.isActive() }
                        delay(if (hasActive) POLL_ACTIVE_MS else POLL_IDLE_MS)
                    }
                    is NetworkResult.Error -> {
                        if (_uiState.value !is TransfersUiState.Success) {
                            _uiState.value = TransfersUiState.Error(result.message)
                        }
                        delay(POLL_IDLE_MS)
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    fun refresh() {
        startPolling(immediate = true)
    }

    private fun buildGroupedMap(transfers: List<PutioTransfer>): Map<TransferGroup, List<PutioTransfer>> {
        val grouped = transfers.groupBy { it.group() }
        // Preserve insertion order for queued items (that's their queue order on put.io)
        return buildMap {
            TransferGroup.entries.forEach { group ->
                val items = grouped[group] ?: return@forEach
                put(group, items)
            }
        }
    }

    companion object {
        private const val POLL_ACTIVE_MS = 5_000L
        private const val POLL_IDLE_MS = 30_000L
    }
}
