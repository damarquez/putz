package com.damarquez.putz.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.MergedTransfer
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TransferGroup
import com.damarquez.putz.data.model.group
import com.damarquez.putz.data.model.isActive
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.TransfersRepository
import com.damarquez.putz.oauth.PendingMagnetRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TransfersUiState {
    data object Loading : TransfersUiState()
    data class Success(
        val grouped: Map<TransferGroup, List<MergedTransfer>>,
        val isRefreshing: Boolean = false,
    ) : TransfersUiState()
    data class Error(val message: String) : TransfersUiState()
}

sealed class AddTransferState {
    data object Idle : AddTransferState()
    data object Submitting : AddTransferState()
    data class Failed(val message: String) : AddTransferState()
}

sealed class TransfersNavigationEvent {
    data class NavigateToFiles(val parentId: Long, val folderName: String, val highlightFileId: Long) : TransfersNavigationEvent()
}

@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val transfersRepository: TransfersRepository,
    private val filesRepository: com.damarquez.putz.data.repository.FilesRepository,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingMagnetRepository: PendingMagnetRepository,
) : ViewModel() {

    // Tracks last-registered "status:displayName" per transfer ID to avoid redundant daemon calls
    private val registeredKeys = mutableMapOf<Long, String>()

    private val _uiState = MutableStateFlow<TransfersUiState>(TransfersUiState.Loading)
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    private val _showAddSheet = MutableStateFlow(false)
    val showAddSheet: StateFlow<Boolean> = _showAddSheet.asStateFlow()

    private val _prefillMagnet = MutableStateFlow("")
    val prefillMagnet: StateFlow<String> = _prefillMagnet.asStateFlow()

    private val _addState = MutableStateFlow<AddTransferState>(AddTransferState.Idle)
    val addState: StateFlow<AddTransferState> = _addState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<TransfersNavigationEvent?>(null)
    val navigationEvent: StateFlow<TransfersNavigationEvent?> = _navigationEvent.asStateFlow()

    private var pollJob: Job? = null

    init {
        startPolling()
        observePendingMagnet()
    }

    private fun observePendingMagnet() {
        viewModelScope.launch {
            pendingMagnetRepository.pending.filterNotNull().collect { magnet ->
                _prefillMagnet.value = magnet
                _addState.value = AddTransferState.Idle
                _showAddSheet.value = true
                pendingMagnetRepository.clear()
            }
        }
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
                when (val result = transfersRepository.syncAndGetTransfers(token)) {
                    is NetworkResult.Success -> {
                        val transfers = result.data
                        _uiState.value = TransfersUiState.Success(grouped = buildGroupedMap(transfers))
                        val hasActive = transfers.any { it.transfer.isActive() }
                        registerTransferHistory(transfers, token)
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

    fun refresh() = startPolling(immediate = true)

    fun openAddSheet(prefill: String = "") {
        _prefillMagnet.value = prefill
        _addState.value = AddTransferState.Idle
        _showAddSheet.value = true
    }

    fun dismissAddSheet() {
        _showAddSheet.value = false
        _addState.value = AddTransferState.Idle
    }

    fun submitTransfer(magnetOrUrl: String) {
        if (_addState.value is AddTransferState.Submitting) return
        _addState.value = AddTransferState.Submitting
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = transfersRepository.addTransfer(token, magnetOrUrl)) {
                is NetworkResult.Success -> {
                    _showAddSheet.value = false
                    _addState.value = AddTransferState.Idle
                    startPolling(immediate = true)
                }
                is NetworkResult.Error -> {
                    _addState.value = AddTransferState.Failed(result.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun stopTransfer(id: Long) {
        println("TransfersViewModel: Stopping transfer $id")
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = transfersRepository.stopTransfer(token, id)
            println("TransfersViewModel: Stop result: $result")
            refresh()
        }
    }

    fun removeTransfer(id: Long) {
        println("TransfersViewModel: Removing transfer $id")
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = transfersRepository.removeTransfer(token, id)
            println("TransfersViewModel: Remove result: $result")
            refresh()
        }
    }

    fun resumeTransfer(id: Long) {
        println("TransfersViewModel: Resuming transfer $id")
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = transfersRepository.resumeTransfer(token, id)
            println("TransfersViewModel: Resume result: $result")
            refresh()
        }
    }

    fun updateDisplayName(id: Long, newName: String) {
        viewModelScope.launch {
            transfersRepository.updateDisplayName(id, newName)
            // Invalidate cached registration key so the next poll re-registers with the new label
            registeredKeys.remove(id)
            refresh()
        }
    }

    // CONTRACT: REGISTER_TRANSFER_HISTORY — fire-and-forget registration for changed transfers
    private fun registerTransferHistory(transfers: List<MergedTransfer>, putioToken: String) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) return@launch
            for (t in transfers) {
                val hash = t.transfer.hash?.takeIf { it.isNotBlank() } ?: continue
                val key = "${t.transfer.status}:${t.appDisplayName}"
                if (registeredKeys[t.transfer.id] == key) continue
                registeredKeys[t.transfer.id] = key
                calibreRepository.registerTransferHistory(
                    putioTransferId = t.transfer.id,
                    infoHash = hash,
                    label = t.appDisplayName,
                    putioName = t.transfer.name,
                    magnetUri = t.magnetLink,
                    putioId = t.transfer.id,
                    status = t.transfer.status,
                    googleAccount = googleAccount,
                )
            }
        }
    }

    fun goToFiles(fileId: Long) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.getFile(token, fileId)) {
                is NetworkResult.Success -> {
                    val file = result.data
                    // If it's the root or we don't know the parent name, just use a default or fetch it
                    // For now, let's assume if it's not root, we might want the parent's info.
                    // But the Screen.Files expects the folder name to DISPLAY. 
                    // If we navigate to parentId, the name should be the parent's name.
                    
                    var targetParentId = file.parentId
                    var targetFolderName = "Your Files" // Default for root

                    if (targetParentId != 0L) {
                        when (val parentResult = filesRepository.getFile(token, targetParentId)) {
                            is NetworkResult.Success -> {
                                targetFolderName = parentResult.data.name
                            }
                            else -> { /* keep default */ }
                        }
                    }

                    _navigationEvent.value = TransfersNavigationEvent.NavigateToFiles(
                        parentId = targetParentId,
                        folderName = targetFolderName,
                        highlightFileId = file.id
                    )
                }
                is NetworkResult.Error -> {
                    // Maybe show a snackbar?
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun onNavigationHandled() {
        _navigationEvent.value = null
    }

    private fun buildGroupedMap(transfers: List<MergedTransfer>): Map<TransferGroup, List<MergedTransfer>> {
        val grouped = transfers.groupBy { it.transfer.group() }
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
