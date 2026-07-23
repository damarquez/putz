package com.damarquez.putz.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.AddTransferOutcome
import com.damarquez.putz.data.model.HistoryEntryFile
import com.damarquez.putz.data.model.HistoryFileEntry
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.TransferHistoryRepository
import com.damarquez.putz.data.repository.TransfersRepository
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchState(val query: String, val inFiles: Boolean)

/** Status bucket for the history search filter dropdown. History entry status is a loose
 *  string (see [HistoryFileEntry.status]) sourced from whatever put.io/daemon status was
 *  current when the entry was written, so this groups the many possible raw values into the
 *  buckets a user actually cares about rather than listing every raw string. */
enum class HistoryStatusFilter(val label: String) {
    ALL("All"),
    COMPLETED("Completed"),
    ERROR("Error"),
    WAITING("Waiting"),
    DOWNLOADING("Downloading"),
    OTHER("Other"),
}

private fun HistoryFileEntry.matchesStatusFilter(filter: HistoryStatusFilter): Boolean {
    val isCompleted = status.equals("COMPLETED", ignoreCase = true)
    // ABANDONED: the daemon's staleness sweep concluded this entry is no longer an active
    // put.io transfer (unseen for several scan cycles) without ever reaching a real terminal
    // status — closest in meaning to an error/dead entry for filtering purposes.
    val isError = status.equals("ERROR", ignoreCase = true) || status.equals("FAILED", ignoreCase = true) ||
        status.equals("ABANDONED", ignoreCase = true)
    val isWaiting = status.equals("WAITING", ignoreCase = true) ||
        status.equals("IN_QUEUE", ignoreCase = true) ||
        status.equals("QUEUED_OUTSIDE_PUTIO", ignoreCase = true)
    val isDownloading = status.equals("DOWNLOADING", ignoreCase = true) ||
        status.equals("PREPARING_DOWNLOAD", ignoreCase = true) ||
        status.equals("COMPLETING", ignoreCase = true)
    return when (filter) {
        HistoryStatusFilter.ALL -> true
        HistoryStatusFilter.COMPLETED -> isCompleted
        HistoryStatusFilter.ERROR -> isError
        HistoryStatusFilter.WAITING -> isWaiting
        HistoryStatusFilter.DOWNLOADING -> isDownloading
        HistoryStatusFilter.OTHER -> !isCompleted && !isError && !isWaiting && !isDownloading
    }
}

sealed class HistoryUiState {
    data object Loading : HistoryUiState()
    data class Success(val entries: List<HistoryFileEntry>) : HistoryUiState()
    data class Error(val message: String) : HistoryUiState()
}

@HiltViewModel
class TransferHistoryViewModel @Inject constructor(
    private val calibreRepository: CalibreRepository,
    private val historyRepository: TransferHistoryRepository,
    private val transfersRepository: TransfersRepository,
    private val settingsRepository: SettingsRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchInFiles = MutableStateFlow(false)
    val searchInFiles: StateFlow<Boolean> = _searchInFiles.asStateFlow()

    private val _statusFilter = MutableStateFlow(HistoryStatusFilter.ALL)
    val statusFilter: StateFlow<HistoryStatusFilter> = _statusFilter.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    // Info hashes matched by the last server-side file search (SEARCH_HISTORY_FILES) — per-file
    // data is never shipped in the routine history JSON, so "search in files" is pushed down to
    // the daemon's SQLite instead of filtering an in-memory list. Null = no search in flight/needed.
    private val _fileSearchMatches = MutableStateFlow<Set<String>?>(null)
    private val _fileSearchInProgress = MutableStateFlow(false)
    val fileSearchInProgress: StateFlow<Boolean> = _fileSearchInProgress.asStateFlow()

    // Per-entry file listings fetched on demand (FETCH_HISTORY_FILES) for the detail view,
    // cached for the session so reopening an entry doesn't refetch.
    private val _entryFiles = MutableStateFlow<Map<String, List<HistoryEntryFile>>>(emptyMap())
    val entryFiles: StateFlow<Map<String, List<HistoryEntryFile>>> = _entryFiles.asStateFlow()
    private val _entryFilesLoading = MutableStateFlow<Set<String>>(emptySet())
    val entryFilesLoading: StateFlow<Set<String>> = _entryFilesLoading.asStateFlow()

    val filteredEntries: StateFlow<List<HistoryFileEntry>> = combine(
        _uiState, _searchQuery, _searchInFiles, _statusFilter, _fileSearchMatches,
    ) { state, query, inFiles, statusFilter, fileMatches ->
        val entries = (state as? HistoryUiState.Success)?.entries ?: return@combine emptyList()
        val statusMatched = entries.filter { it.matchesStatusFilter(statusFilter) }
        if (query.isBlank()) statusMatched
        else {
            val q = query.trim().lowercase()
            statusMatched.filter { e ->
                e.label.lowercase().contains(q) ||
                e.resolvedName?.lowercase()?.contains(q) == true ||
                e.putioName?.lowercase()?.contains(q) == true ||
                e.infoHash.lowercase().contains(q) ||
                (inFiles && fileMatches?.contains(e.infoHash.lowercase()) == true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSearchInFiles(enabled: Boolean) { _searchInFiles.value = enabled }
    fun setStatusFilter(filter: HistoryStatusFilter) { _statusFilter.value = filter }

    init {
        // Trigger a load immediately and again whenever the daemon heartbeat delivers a new
        // history file ID (e.g. after the daemon uploads a fresh version of the JSON).
        viewModelScope.launch {
            settingsRepository.historyFileIdFlow
                .distinctUntilChanged()
                .collect { loadHistory() }
        }

        // Debounced server-side file search — only fires while "search in files" is on and the
        // query is non-blank. LAN responses are near-instant; a Drive-only fallback can take up
        // to ~30s, hence the in-progress flag rather than blocking the list.
        viewModelScope.launch {
            combine(_searchQuery, _searchInFiles) { query, inFiles -> query to inFiles }
                .debounce(400)
                .distinctUntilChanged()
                .collect { (query, inFiles) ->
                    if (!inFiles || query.isBlank()) {
                        _fileSearchMatches.value = null
                        _fileSearchInProgress.value = false
                        return@collect
                    }
                    _fileSearchInProgress.value = true
                    val results = historyRepository.searchFiles(query.trim())
                    _fileSearchMatches.value = results?.map { it.infoHash.lowercase() }?.toSet() ?: emptySet()
                    _fileSearchInProgress.value = false
                }
        }
    }

    /** Fetches (and caches for the session) the file listing for one entry — called when the
     *  detail sheet opens, since per-file data is never included in the routine history sync. */
    fun loadFilesForEntry(infoHash: String) {
        val hash = infoHash.lowercase()
        if (_entryFiles.value.containsKey(hash) || hash in _entryFilesLoading.value) return
        _entryFilesLoading.value = _entryFilesLoading.value + hash
        viewModelScope.launch {
            val files = historyRepository.fetchEntryFiles(hash)
            _entryFiles.value = _entryFiles.value + (hash to (files ?: emptyList()))
            _entryFilesLoading.value = _entryFilesLoading.value - hash
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            val token = secureStorage.authTokenFlow.value
            if (token.isBlank()) {
                _uiState.value = HistoryUiState.Error("Not authenticated with put.io")
                return@launch
            }

            // Don't flash a spinner when we already have data — refresh silently
            if (_uiState.value !is HistoryUiState.Success) {
                _uiState.value = HistoryUiState.Loading
            }

            val fileId = settingsRepository.historyFileIdFlow.first()
            if (fileId == null) {
                // No heartbeat received yet. Show persisted cache if we have one so the
                // screen isn't blank; otherwise keep the spinner — the flow observer above
                // will call loadHistory() again as soon as a heartbeat arrives.
                val cached = historyRepository.getCachedHistory()
                if (cached != null) {
                    _uiState.value = HistoryUiState.Success(cached.entries.sortedByDescending { it.addedAt })
                }
                return@launch
            }

            val history = historyRepository.fetchHistory(token)
            when {
                history != null ->
                    _uiState.value = HistoryUiState.Success(history.entries.sortedByDescending { it.addedAt })
                _uiState.value is HistoryUiState.Success ->
                    Unit // Keep stale cached data visible; silently swallow the refresh failure
                else ->
                    _uiState.value = HistoryUiState.Error("Could not load history — pull to refresh")
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

            val now = System.currentTimeMillis()
            calibreRepository.registerTransferHistory(
                putioTransferId = entry.putioId ?: now,
                infoHash = infoHash,
                label = newLabel,
                labelUpdatedAt = now,
                putioName = entry.putioName,
                magnetUri = entry.magnetUri,
                putioId = entry.putioId,
                status = entry.status,
                googleAccount = googleAccount,
            )

            // Sync the local transfer display name so subsequent background polls from this
            // device don't overwrite this edit by re-registering the old label.
            entry.putioId?.let { transfersRepository.updateDisplayName(it, newLabel) }
        }
    }

    fun onActionMessageShown() {
        _actionMessage.value = null
    }

    /** Re-submits a history entry's magnet to put.io. If put.io's transfer limit is still
     *  reached, the entry is flagged QUEUED_OUTSIDE_PUTIO instead of failing outright — same
     *  retry semantics as [TransfersViewModel.activateQueued], exposed here for any history entry. */
    fun activateEntry(entry: HistoryFileEntry) {
        viewModelScope.launch {
            val token = secureStorage.authTokenFlow.value
            if (token.isBlank()) {
                _actionMessage.value = "Not authenticated with put.io"
                return@launch
            }

            when (val outcome = transfersRepository.activateHistoryEntry(token, entry)) {
                is AddTransferOutcome.Added -> {
                    applyEntryUpdate(entry.copy(status = outcome.merged.transfer.status, putioId = outcome.merged.transfer.id))
                    _actionMessage.value = "Added to put.io"
                }
                is AddTransferOutcome.Queued -> {
                    applyEntryUpdate(outcome.entry)
                    _actionMessage.value = "Still at the transfer limit — queued for later"
                }
                is AddTransferOutcome.Failed -> {
                    _actionMessage.value = outcome.message
                }
            }
        }
    }

    /** Manual correction for entries stuck showing a stale non-terminal status even though the
     *  transfer actually completed (e.g. rows written before the daemon's sticky-COMPLETED guard
     *  existed). Once set, COMPLETED can never be downgraded again by any later poll/scan. */
    fun markAsCompleted(entry: HistoryFileEntry) {
        if (entry.status == "COMPLETED") return
        val previous = entry
        applyEntryUpdate(entry.copy(status = "COMPLETED"))

        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                applyEntryUpdate(previous)
                _actionMessage.value = "Not authenticated with Google Drive"
                return@launch
            }

            val confirmed = calibreRepository.registerTransferHistory(
                putioTransferId = entry.putioId ?: System.currentTimeMillis(),
                infoHash = entry.infoHash,
                label = entry.label,
                putioName = entry.putioName,
                magnetUri = entry.magnetUri,
                putioId = entry.putioId,
                status = "COMPLETED",
                googleAccount = googleAccount,
            )
            if (!confirmed) {
                applyEntryUpdate(previous)
                _actionMessage.value = "Couldn't mark as completed — check your connection and try again"
            } else {
                _actionMessage.value = "Marked as completed"
            }
        }
    }

    /** Cancels the put.io transfer job (best-effort — the entry may have no `putioId`, or put.io
     *  may have already cleared it; either way that shouldn't block the part the user actually
     *  asked for) and permanently deletes the local history record for [entry], so the same
     *  info_hash can be re-added/re-downloaded afterward without tripping the "Already in
     *  history" duplicate check. Unlike every other history mutation, this really does remove
     *  the row — see `TransferHistoryService.forget`'s docstring on the daemon side for why that
     *  never otherwise happens. */
    fun removeFromHistory(entry: HistoryFileEntry) {
        if (entry.status != "COMPLETED") return
        viewModelScope.launch {
            val token = secureStorage.authTokenFlow.value
            if (token.isNotBlank()) {
                transfersRepository.forgetCompletedTransfer(token, entry)
            }
            if (historyRepository.forgetEntry(entry.infoHash)) {
                removeEntryFromUiState(entry.infoHash)
                _actionMessage.value = "Removed from history — can be re-added now"
            } else {
                _actionMessage.value = "Couldn't remove from history — check your connection and try again"
            }
        }
    }

    private fun applyEntryUpdate(entry: HistoryFileEntry) {
        val current = _uiState.value as? HistoryUiState.Success ?: return
        _uiState.value = HistoryUiState.Success(
            current.entries.map { if (it.infoHash == entry.infoHash) entry else it }
        )
    }

    private fun removeEntryFromUiState(infoHash: String) {
        val current = _uiState.value as? HistoryUiState.Success ?: return
        _uiState.value = HistoryUiState.Success(current.entries.filterNot { it.infoHash == infoHash })
    }
}
