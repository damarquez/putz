package com.damarquez.putz.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.HistoryFileEntry
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.TransferHistoryRepository
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HistoryUiState {
    data object Loading : HistoryUiState()
    data class Success(val entries: List<HistoryFileEntry>) : HistoryUiState()
    data class Error(val message: String) : HistoryUiState()
}

@HiltViewModel
class TransferHistoryViewModel @Inject constructor(
    private val calibreRepository: CalibreRepository,
    private val historyRepository: TransferHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredEntries: StateFlow<List<HistoryFileEntry>> = combine(_uiState, _searchQuery) { state, query ->
        val entries = (state as? HistoryUiState.Success)?.entries ?: return@combine emptyList()
        if (query.isBlank()) entries
        else {
            val q = query.trim().lowercase()
            entries.filter { e ->
                e.label.lowercase().contains(q) ||
                e.resolvedName?.lowercase()?.contains(q) == true ||
                e.putioName?.lowercase()?.contains(q) == true ||
                e.infoHash.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _uiState.value = HistoryUiState.Loading
            val token = secureStorage.authTokenFlow.value
            if (token.isBlank()) {
                _uiState.value = HistoryUiState.Error("Not authenticated")
                return@launch
            }
            val history = historyRepository.fetchHistory(token)
            _uiState.value = when {
                history == null -> HistoryUiState.Error(
                    "History not available — make sure the daemon is running and has a put.io token configured"
                )
                else -> HistoryUiState.Success(history.entries.sortedByDescending { it.addedAt })
            }
        }
    }

    fun updateLabel(infoHash: String, newLabel: String) {
        val current = _uiState.value as? HistoryUiState.Success ?: return
        val entry = current.entries.firstOrNull { it.infoHash == infoHash } ?: return

        // Optimistic update
        _uiState.value = HistoryUiState.Success(
            current.entries.map { if (it.infoHash == infoHash) it.copy(label = newLabel) else it }
        )

        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) return@launch
            calibreRepository.registerTransferHistory(
                putioTransferId = entry.putioId ?: System.currentTimeMillis(),
                infoHash = infoHash,
                label = newLabel,
                putioName = entry.putioName,
                magnetUri = entry.magnetUri,
                putioId = entry.putioId,
                status = entry.status,
                googleAccount = googleAccount,
            )
        }
    }
}
