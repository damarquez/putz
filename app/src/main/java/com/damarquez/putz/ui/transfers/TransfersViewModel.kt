package com.damarquez.putz.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.AddTransferOutcome
import com.damarquez.putz.data.model.HistoryFileEntry
import com.damarquez.putz.data.model.MergedTransfer
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.model.TransferGroup
import com.damarquez.putz.data.model.TransferStatus
import com.damarquez.putz.data.model.group
import com.damarquez.putz.data.model.isActive
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.TransferHistoryRepository
import com.damarquez.putz.data.repository.TransfersRepository
import com.damarquez.putz.oauth.PendingMagnetRepository
import com.damarquez.putz.util.MagnetParser
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
    /** Magnet was found in transfer history; user must confirm before adding. */
    data class HistoryMatch(val entry: HistoryFileEntry, val magnetOrUrl: String, val hideFromDaemon: Boolean = false) : AddTransferState()
}

sealed class TransfersNavigationEvent {
    data class NavigateToFiles(val parentId: Long, val folderName: String, val highlightFileId: Long) : TransfersNavigationEvent()
}

@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val transfersRepository: TransfersRepository,
    private val filesRepository: com.damarquez.putz.data.repository.FilesRepository,
    private val calibreRepository: CalibreRepository,
    private val historyRepository: TransferHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingMagnetRepository: PendingMagnetRepository,
) : ViewModel() {

    // Tracks last-registered "status:displayName" per transfer ID to avoid redundant daemon calls
    private val registeredKeys = mutableMapOf<Long, String>()

    // Cached history keyed by lowercase info hash; refreshed on init and manual refresh
    private var historyByHash: Map<String, HistoryFileEntry> = emptyMap()

    // Last successfully-fetched put.io transfers, so loadHistory() can rebuild the grouped
    // map (including the history-derived "waiting for a free slot" section) without re-polling.
    private var lastTransfers: List<MergedTransfer> = emptyList()

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

    private val _resumeError = MutableStateFlow<String?>(null)
    val resumeError: StateFlow<String?> = _resumeError.asStateFlow()

    private val _queuedMessage = MutableStateFlow<String?>(null)
    val queuedMessage: StateFlow<String?> = _queuedMessage.asStateFlow()

    private var pollJob: Job? = null

    init {
        startPolling()
        observePendingMagnet()
        loadHistory()
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
                        lastTransfers = transfers
                        _uiState.value = TransfersUiState.Success(grouped = buildGroupedMap(transfers))
                        registerTransferHistory(transfers, token)

                        val seeding = transfers.filter {
                            TransferStatus.from(it.transfer.status) == TransferStatus.SEEDING && !it.isStopped
                        }
                        if (seeding.isNotEmpty()) {
                            seeding.forEach { transfersRepository.stopTransfer(token, it.transfer.id) }
                            // re-poll immediately so the UI reflects the cancellations
                        } else {
                            val hasActive = transfers.any { it.transfer.isActive() }
                            delay(if (hasActive) POLL_ACTIVE_MS else POLL_IDLE_MS)
                        }
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
        loadHistory()
        startPolling(immediate = true)
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = historyRepository.fetchHistory(token) ?: return@launch
            historyByHash = result.entries.associateBy { it.infoHash.lowercase() }

            // Persist any resolved names that are not yet in the local DB.
            // Returns true if anything changed, so we trigger a fresh poll to reload the names.
            val resolvedNames = result.entries
                .mapNotNull { e -> e.resolvedName?.takeIf { it.isNotBlank() }?.let { e.infoHash.lowercase() to it } }
                .toMap()
            val anyPersisted = transfersRepository.applyHistoryNames(resolvedNames)

            if (anyPersisted) {
                // Re-poll so the updated displayNames from DB are loaded into state
                startPolling(immediate = true)
            } else {
                // Nothing persisted — rebuild from the last known transfers so the
                // "waiting for a free slot" section (sourced from history) stays current.
                val current = _uiState.value as? TransfersUiState.Success ?: return@launch
                _uiState.value = current.copy(grouped = buildGroupedMap(lastTransfers))
            }
        }
    }

    fun openAddSheet(prefill: String = "") {
        _prefillMagnet.value = prefill
        _addState.value = AddTransferState.Idle
        _showAddSheet.value = true
        // Refresh historyByHash so a recent "Remove from history" (done on a different screen,
        // this ViewModel otherwise wouldn't hear about) doesn't still show a stale "Already in
        // history" warning for an entry that's actually gone now.
        loadHistory()
    }

    fun dismissAddSheet() {
        _showAddSheet.value = false
        _addState.value = AddTransferState.Idle
    }

    fun submitTransfer(magnetOrUrl: String, hideFromDaemon: Boolean = false) {
        if (_addState.value is AddTransferState.Submitting) return
        // Check transfer history for duplicates before submitting
        if (MagnetParser.isMagnetLink(magnetOrUrl)) {
            val hash = MagnetParser.extractInfoHash(magnetOrUrl)?.lowercase()
            if (hash != null) {
                val match = historyByHash[hash]
                if (match != null) {
                    _addState.value = AddTransferState.HistoryMatch(match, magnetOrUrl, hideFromDaemon)
                    return
                }
            }
        }
        doSubmitTransfer(magnetOrUrl, hideFromDaemon)
    }

    // bypassHistoryCheck=true: the user already confirmed through this exact warning once (this
    // is the "Add anyway" override) — must actually skip TransfersRepository's own internal
    // completed-in-history guard too, not just this screen's client-side warning, or the user
    // gets stuck in an unbreakable loop between the two checks (see TransfersRepository.addTransfer).
    fun submitTransferAnyway(magnetOrUrl: String, hideFromDaemon: Boolean = false) =
        doSubmitTransfer(magnetOrUrl, hideFromDaemon, bypassHistoryCheck = true)

    private fun doSubmitTransfer(magnetOrUrl: String, hideFromDaemon: Boolean = false, bypassHistoryCheck: Boolean = false) {
        _addState.value = AddTransferState.Submitting
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val saveParentId = if (hideFromDaemon) transfersRepository.getOrCreateHiddenFolderId(token) else 0L
            when (val outcome = transfersRepository.addTransfer(token, magnetOrUrl, saveParentId, bypassHistoryCheck = bypassHistoryCheck)) {
                is AddTransferOutcome.Added -> {
                    _showAddSheet.value = false
                    _addState.value = AddTransferState.Idle
                    startPolling(immediate = true)
                }
                is AddTransferOutcome.Queued -> {
                    applyHistoryUpdate(outcome.entry)
                    _showAddSheet.value = false
                    _addState.value = AddTransferState.Idle
                    _queuedMessage.value = "Active transfer limit reached — saved to history, tap Activate when ready"
                }
                is AddTransferOutcome.Failed -> {
                    _addState.value = AddTransferState.Failed(outcome.message)
                }
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

    fun onQueuedMessageShown() {
        _queuedMessage.value = null
    }

    /** User explicitly asked to retry a magnet that's flagged QUEUED_OUTSIDE_PUTIO in history.
     *  This never runs on its own — put.io manages its own queue below the limit; above it,
     *  only an explicit tap here ever calls /transfers/add again. */
    fun activateQueued(entry: HistoryFileEntry) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val outcome = transfersRepository.activateHistoryEntry(token, entry)) {
                is AddTransferOutcome.Added -> {
                    applyHistoryUpdate(entry.copy(status = outcome.merged.transfer.status, putioId = outcome.merged.transfer.id))
                    startPolling(immediate = true)
                }
                is AddTransferOutcome.Queued -> {
                    applyHistoryUpdate(outcome.entry)
                    _queuedMessage.value = "Still at the transfer limit — try again later"
                }
                is AddTransferOutcome.Failed -> {
                    _resumeError.value = outcome.message
                }
            }
        }
    }

    fun cancelQueued(entry: HistoryFileEntry) {
        viewModelScope.launch {
            val updated = transfersRepository.cancelQueuedHistoryEntry(entry)
            if (updated != null) {
                applyHistoryUpdate(updated)
            } else {
                // Couldn't confirm the cancel reached shared history — leave it showing rather
                // than hiding something that might still be queued from another device's view.
                _resumeError.value = "Couldn't reach shared history to cancel — try again"
            }
        }
    }

    /** Splices a freshly-registered history entry into the cache and rebuilds the grouped map
     *  immediately, instead of waiting on the daemon's upload-then-refetch round trip. */
    private fun applyHistoryUpdate(entry: HistoryFileEntry) {
        historyByHash = historyByHash + (entry.infoHash.lowercase() to entry)
        val current = _uiState.value as? TransfersUiState.Success ?: return
        _uiState.value = current.copy(grouped = buildGroupedMap(lastTransfers))
    }

    fun resumeTransfer(id: Long) {
        println("TransfersViewModel: Resuming transfer $id")
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = transfersRepository.resumeTransfer(token, id)
            println("TransfersViewModel: Resume result: $result")
            if (result is NetworkResult.Error) {
                _resumeError.value = result.message
            }
            refresh()
        }
    }

    fun onResumeErrorShown() {
        _resumeError.value = null
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

    /** Called when the user taps a transfer that has a history entry. If the current display
     *  name is still a hash, immediately persists the resolved name from history. */
    fun onTransferTapped(merged: MergedTransfer) {
        val resolved = merged.historyEntry?.resolvedName?.takeIf { it.isNotBlank() } ?: return
        val currentName = merged.appDisplayName
        // Only act if the current name looks like a bare hash (pure hex, likely unresolved)
        if (!currentName.matches(Regex("[0-9a-fA-F]+"))) return
        viewModelScope.launch {
            transfersRepository.persistNameById(merged.transfer.id, resolved)
            startPolling(immediate = true)
        }
    }

    private fun buildGroupedMap(transfers: List<MergedTransfer>): Map<TransferGroup, List<MergedTransfer>> {
        val activeHashes = transfers.mapNotNull { it.transfer.hash?.lowercase() }.toSet()
        val enriched = transfers.map { t ->
            val entry = t.transfer.hash?.lowercase()?.let { historyByHash[it] }
            if (entry != null) t.copy(historyEntry = entry) else t
        }
        val grouped = enriched.groupBy { it.transfer.group() }

        // Entries the daemon's history still flags as queued-outside-put.io, but that already
        // exist as a real put.io transfer (e.g. another device Activated it) shouldn't double-show.
        val queuedOutside = historyByHash.values
            .filter { it.status == TransfersRepository.HISTORY_STATUS_QUEUED_OUTSIDE_PUTIO }
            .filter { it.infoHash.lowercase() !in activeHashes }

        return buildMap {
            if (queuedOutside.isNotEmpty()) {
                put(TransferGroup.PENDING_LOCAL, queuedOutside.map { it.toMergedTransfer() })
            }
            TransferGroup.entries.forEach { group ->
                if (group == TransferGroup.PENDING_LOCAL) return@forEach
                val items = grouped[group] ?: return@forEach
                put(group, items)
            }
        }
    }

    private fun HistoryFileEntry.toMergedTransfer(): MergedTransfer {
        // Stable per-hash sentinel — never a real put.io ID — just so the UI has a list key.
        // Actions on these entries go through historyEntry directly, never through this ID.
        val sentinelId = -(infoHash.hashCode().toLong() and 0x7fffffffL) - 1
        // Same precedence used everywhere else history is shown: the daemon's independently
        // metadata-enriched resolvedName wins over whatever label happened to be registered.
        val displayName = resolvedName?.takeIf { it.isNotBlank() }
            ?: putioName?.takeIf { it.isNotBlank() }
            ?: label
        return MergedTransfer(
            transfer = PutioTransfer(id = sentinelId, name = displayName, status = "WAITING"),
            appDisplayName = displayName,
            magnetLink = magnetUri,
            addedByApp = true,
            isPendingLocal = true,
            historyEntry = this,
        )
    }

    companion object {
        private const val POLL_ACTIVE_MS = 5_000L
        private const val POLL_IDLE_MS = 30_000L
    }
}
