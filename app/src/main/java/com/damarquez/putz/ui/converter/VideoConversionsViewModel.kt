package com.damarquez.putz.ui.converter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.converter.ConverterItem
import com.damarquez.putz.data.converter.ConverterSearchResult
import com.damarquez.putz.data.converter.ConverterStatus
import com.damarquez.putz.data.converter.VideoConverterClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConverterTab(val label: String) { QUEUE("Queue"), HISTORY("History"), ADD("Add") }

/** History order; [api] is the converter's sort key (CONTRACTS.md §48). */
enum class HistorySort(val label: String, val api: String) {
    RECENT("Newest first", "finished"),
    NAME("Name", "name"),
    STATUS("Status", "status"),
}

private const val HISTORY_PAGE = 200

data class VideoConversionsUiState(
    val status: ConverterStatus? = null,
    val queue: List<ConverterItem> = emptyList(),
    val queueTotal: Int = 0,
    val history: List<ConverterItem> = emptyList(),
    val historyTotal: Int = 0,
    val historySort: HistorySort = HistorySort.RECENT,
    val historyLimit: Int = HISTORY_PAGE,
    val tab: ConverterTab = ConverterTab.QUEUE,
    val searchQuery: String = "",
    val searchResults: List<ConverterSearchResult> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class VideoConversionsViewModel @Inject constructor(
    private val client: VideoConverterClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoConversionsUiState())
    val uiState: StateFlow<VideoConversionsUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private var pollJob: Job? = null
    private var searchJob: Job? = null

    /** Polls while the screen is visible (started/stopped by the screen's lifecycle). */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(if (_uiState.value.status?.state == "converting") 3_000 else 10_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refresh() {
        try {
            val status = client.status()
            val (queue, total) = client.queue()
            val s = _uiState.value
            val (history, historyTotal) = if (s.tab == ConverterTab.HISTORY || !s.loaded)
                client.history(s.historyLimit, s.historySort.api) else s.history to s.historyTotal
            _uiState.update {
                // Drop a page that was fetched for a sort the user has since changed.
                if (it.historySort != s.historySort) it.copy(status = status, queue = queue, queueTotal = total)
                else it.copy(status = status, queue = queue, queueTotal = total, history = history,
                             historyTotal = historyTotal, error = null, loaded = true)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Video converter unreachable", loaded = true) }
        }
    }

    fun setHistorySort(sort: HistorySort) {
        if (sort == _uiState.value.historySort) return
        _uiState.update { it.copy(historySort = sort, historyLimit = HISTORY_PAGE, history = emptyList()) }
        viewModelScope.launch { refresh() }
    }

    fun loadMoreHistory() {
        _uiState.update { it.copy(historyLimit = it.historyLimit + HISTORY_PAGE) }
        viewModelScope.launch { refresh() }
    }

    fun selectTab(tab: ConverterTab) {
        _uiState.update { it.copy(tab = tab) }
        if (tab == ConverterTab.HISTORY) viewModelScope.launch { refresh() }
    }

    private fun act(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _messages.value = success
                refresh()
            } catch (e: Exception) {
                _messages.value = e.message ?: "Failed"
            }
        }
    }

    fun moveToFront(item: ConverterItem) = act("“${item.title}” is next") { client.moveToFront(item.id) }

    fun remove(item: ConverterItem) = act("Removed from the queue") { client.remove(item.id) }

    fun useReviewCopy(item: ConverterItem) =
        act("Replacing the original with the reviewed file — queued next") { client.useReviewCopy(item.id) }

    fun deleteReviewCopy(item: ConverterItem) =
        act("Review copy deleted") { client.deleteReviewCopy(item.id) }

    fun retry(item: ConverterItem, force: Boolean) =
        act(if (force) "Converting anyway — queued next" else "Queued next") { client.retry(item.id, force) }

    fun setPaused(paused: Boolean) =
        act(if (paused) "Paused — the current file finishes first" else "Resumed") { client.setPaused(paused) }

    fun rescan() = act("Library rescan started") { client.rescan() }

    fun enqueue(result: ConverterSearchResult) {
        viewModelScope.launch {
            try {
                val r = client.enqueue(result.ratingKey)
                _messages.value = when {
                    r.queued > 0 -> "Queued next: ${r.queued} file(s)" +
                        if (r.alreadyCompatible > 0) " (${r.alreadyCompatible} already play fine)" else ""
                    r.alreadyCompatible > 0 -> "Already plays directly — nothing to convert"
                    else -> "Nothing to convert in that library"
                }
                refresh()
                search(_uiState.value.searchQuery, immediate = true)
            } catch (e: Exception) {
                _messages.value = e.message ?: "Failed"
            }
        }
    }

    fun search(query: String, immediate: Boolean = false) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(400)
            _uiState.update { it.copy(searching = true) }
            try {
                val results = client.search(query.trim())
                _uiState.update { it.copy(searchResults = results, searching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(searching = false) }
                _messages.value = e.message ?: "Search failed"
            }
        }
    }

    fun consumeMessage() { _messages.value = null }
}
