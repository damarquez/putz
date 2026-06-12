package com.damarquez.putz.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.MediaType
import com.damarquez.putz.data.model.MetaCandidate
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.TorrentSearchResult
import com.damarquez.putz.data.repository.TorrentSearchRepository
import com.damarquez.putz.data.repository.TransfersRepository
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

enum class SearchMode(val label: String) {
    MOVIES("Movies"),
    TV("TV Shows"),
    OTHER("Other"),
}

data class SearchUiState(
    val mode: SearchMode = SearchMode.MOVIES,
    val query: String = "",
    val season: String = "1",
    val episode: String = "1",
    val isSearching: Boolean = false,
    /** Cinemeta title candidates (Movies/TV). User picks one to fetch torrents. */
    val candidates: List<MetaCandidate> = emptyList(),
    val selectedCandidate: MetaCandidate? = null,
    val results: List<TorrentSearchResult> = emptyList(),
    /** True once a search/stream fetch has completed, so empty lists mean "no matches". */
    val searched: Boolean = false,
    val error: String? = null,
    /** Magnets currently being submitted to put.io. */
    val addingMagnets: Set<String> = emptySet(),
    /** Magnets successfully added this session. */
    val addedMagnets: Set<String> = emptySet(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: TorrentSearchRepository,
    private val transfersRepository: TransfersRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val jackettConfigured: StateFlow<Boolean> = searchRepository.jackettConfiguredFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun onModeChange(mode: SearchMode) {
        if (mode == _uiState.value.mode) return
        _uiState.value = _uiState.value.copy(
            mode = mode,
            candidates = emptyList(),
            selectedCandidate = null,
            results = emptyList(),
            searched = false,
            error = null,
        )
    }

    fun onSeasonChange(value: String) {
        _uiState.value = _uiState.value.copy(season = value.filter { it.isDigit() }.take(3))
    }

    fun onEpisodeChange(value: String) {
        _uiState.value = _uiState.value.copy(episode = value.filter { it.isDigit() }.take(4))
    }

    fun search() {
        val state = _uiState.value
        val query = state.query.trim()
        if (query.isBlank() || state.isSearching) return

        _uiState.value = state.copy(
            isSearching = true,
            candidates = emptyList(),
            selectedCandidate = null,
            results = emptyList(),
            searched = false,
            error = null,
        )
        viewModelScope.launch {
            when (state.mode) {
                SearchMode.MOVIES, SearchMode.TV -> {
                    val type = if (state.mode == SearchMode.MOVIES) MediaType.MOVIE else MediaType.SERIES
                    when (val result = searchRepository.searchMetadata(type, query)) {
                        is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            candidates = result.data,
                            searched = true,
                        )
                        is NetworkResult.Error -> _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            error = result.message,
                        )
                        NetworkResult.Loading -> Unit
                    }
                }
                SearchMode.OTHER -> {
                    when (val result = searchRepository.searchJackett(query)) {
                        is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            results = result.data,
                            searched = true,
                        )
                        is NetworkResult.Error -> _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            error = result.message,
                        )
                        NetworkResult.Loading -> Unit
                    }
                }
            }
        }
    }

    fun selectCandidate(candidate: MetaCandidate) {
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedCandidate = candidate,
            results = emptyList(),
            isSearching = true,
            error = null,
        )
        viewModelScope.launch {
            val result = searchRepository.getStreams(
                candidate = candidate,
                season = state.season.toIntOrNull() ?: 1,
                episode = state.episode.toIntOrNull() ?: 1,
            )
            when (result) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    results = result.data,
                    searched = true,
                )
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = result.message,
                )
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Back from a candidate's torrent list to the candidate list. */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedCandidate = null,
            results = emptyList(),
            error = null,
            isSearching = false,
        )
    }

    fun addToPutio(result: TorrentSearchResult) {
        val state = _uiState.value
        if (result.magnet in state.addingMagnets || result.magnet in state.addedMagnets) return
        _uiState.value = state.copy(addingMagnets = state.addingMagnets + result.magnet)
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val added = transfersRepository.addTransfer(token, result.magnet)) {
                is NetworkResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        addingMagnets = _uiState.value.addingMagnets - result.magnet,
                        addedMagnets = _uiState.value.addedMagnets + result.magnet,
                    )
                    _snackbarMessage.value = "Transfer added to put.io"
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        addingMagnets = _uiState.value.addingMagnets - result.magnet,
                    )
                    _snackbarMessage.value = added.message
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }
}
