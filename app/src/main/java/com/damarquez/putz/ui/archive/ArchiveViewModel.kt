package com.damarquez.putz.ui.archive

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.ArchiveDestination
import com.damarquez.putz.data.model.ArchiveEntry
import com.damarquez.putz.data.model.ArchiveSource
import com.damarquez.putz.data.model.ExtractionProgress
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.ArchiveRepository
import com.damarquez.putz.data.repository.LanFilesRepository
import com.damarquez.putz.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LanPickerState(
    val connectionId: Long,
    val connectionLabel: String,
    val currentPath: String,
    val pathStack: List<String> = emptyList(),
    val dirs: List<PutioFile> = emptyList(),
    val isLoading: Boolean = true,
)

sealed class ArchiveUiState {
    data object Loading : ArchiveUiState()
    data class Error(val message: String) : ArchiveUiState()
    data class Success(
        val allEntries: List<ArchiveEntry>,
        val currentDir: String,
        val dirStack: List<String>,
        val visibleEntries: List<ArchiveEntry>,
        val selectedEntries: Set<ArchiveEntry> = emptySet(),
        val extractionProgress: ExtractionProgress? = null,
    ) : ArchiveUiState() {
        val isSelectionMode get() = selectedEntries.isNotEmpty()
    }
}

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val archiveRepository: ArchiveRepository,
    val lanFilesRepository: LanFilesRepository,
) : ViewModel() {

    val archiveName: String = savedStateHandle[Screen.Archive.ARG_ARCHIVE_NAME] ?: "Archive"
    private val localUri: String? = savedStateHandle[Screen.Archive.ARG_LOCAL_URI]
    private val lanConnectionId: Long = savedStateHandle[Screen.Archive.ARG_LAN_CONNECTION_ID] ?: -1L
    private val lanPath: String? = savedStateHandle[Screen.Archive.ARG_LAN_PATH]

    val source: ArchiveSource = when {
        localUri != null -> ArchiveSource.Local(localUri)
        lanConnectionId != -1L && lanPath != null -> ArchiveSource.Lan(lanConnectionId, lanPath)
        else -> error("ArchiveViewModel: no valid source in saved state")
    }

    private val _uiState = MutableStateFlow<ArchiveUiState>(ArchiveUiState.Loading)
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    private val _lanPickerState = MutableStateFlow<LanPickerState?>(null)
    val lanPickerState: StateFlow<LanPickerState?> = _lanPickerState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = ArchiveUiState.Loading
            runCatching { archiveRepository.listEntries(source) }
                .onSuccess { entries ->
                    _uiState.value = ArchiveUiState.Success(
                        allEntries = entries,
                        currentDir = "",
                        dirStack = emptyList(),
                        visibleEntries = directChildren("", entries),
                    )
                }
                .onFailure { e ->
                    _uiState.value = ArchiveUiState.Error(e.message ?: "Failed to open archive")
                }
        }
    }

    fun enterDirectory(entry: ArchiveEntry) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        val newDir = entry.path
        _uiState.value = s.copy(
            currentDir = newDir,
            dirStack = s.dirStack + s.currentDir,
            visibleEntries = directChildren(newDir, s.allEntries),
            selectedEntries = emptySet(),
        )
    }

    fun navigateUp(): Boolean {
        val s = _uiState.value as? ArchiveUiState.Success ?: return false
        if (s.dirStack.isEmpty()) return false
        val parentDir = s.dirStack.last()
        _uiState.value = s.copy(
            currentDir = parentDir,
            dirStack = s.dirStack.dropLast(1),
            visibleEntries = directChildren(parentDir, s.allEntries),
            selectedEntries = emptySet(),
        )
        return true
    }

    fun toggleSelection(entry: ArchiveEntry) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _uiState.value = s.copy(
            selectedEntries = if (entry in s.selectedEntries)
                s.selectedEntries - entry else s.selectedEntries + entry
        )
    }

    fun clearSelection() {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _uiState.value = s.copy(selectedEntries = emptySet())
    }

    fun extract(destination: ArchiveDestination) {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        val toExtract = if (s.selectedEntries.isEmpty()) s.visibleEntries else s.selectedEntries.toList()
        archiveRepository.extractEntries(source, toExtract, destination, s.currentDir)
            .onEach { progress ->
                _uiState.value = s.copy(
                    extractionProgress = progress,
                    selectedEntries = if (progress is ExtractionProgress.Done) emptySet() else s.selectedEntries,
                )
            }
            .launchIn(viewModelScope)
    }

    fun dismissExtractionResult() {
        val s = _uiState.value as? ArchiveUiState.Success ?: return
        _uiState.value = s.copy(extractionProgress = null)
    }

    fun openLanPicker(connectionId: Long, connectionLabel: String, initialPath: String) {
        // Pre-populate pathStack with every ancestor so the user can navigate up from the start.
        val pathStack: List<String> = if (initialPath.isEmpty()) emptyList() else {
            val parts = initialPath.split('/')
            buildList {
                add("") // share root
                for (i in 0 until parts.size - 1) {
                    add(parts.take(i + 1).joinToString("/"))
                }
            }
        }
        val state = LanPickerState(
            connectionId = connectionId,
            connectionLabel = connectionLabel,
            currentPath = initialPath,
            pathStack = pathStack,
        )
        _lanPickerState.value = state
        loadLanPickerDirs(state)
    }

    fun lanPickerEnterDir(dir: PutioFile) {
        val state = _lanPickerState.value ?: return
        val newState = state.copy(
            currentPath = dir.lanPath ?: return,
            pathStack = state.pathStack + state.currentPath,
            dirs = emptyList(),
            isLoading = true,
        )
        _lanPickerState.value = newState
        loadLanPickerDirs(newState)
    }

    fun lanPickerNavigateUp(): Boolean {
        val state = _lanPickerState.value ?: return false
        if (state.pathStack.isEmpty()) return false
        val parentPath = state.pathStack.last()
        val newState = state.copy(
            currentPath = parentPath,
            pathStack = state.pathStack.dropLast(1),
            dirs = emptyList(),
            isLoading = true,
        )
        _lanPickerState.value = newState
        loadLanPickerDirs(newState)
        return true
    }

    fun closeLanPicker() {
        _lanPickerState.value = null
    }

    fun confirmLanExtraction() {
        val picker = _lanPickerState.value ?: return
        _lanPickerState.value = null
        extract(ArchiveDestination.Lan(picker.connectionId, picker.currentPath))
    }

    private fun loadLanPickerDirs(state: LanPickerState) {
        viewModelScope.launch {
            val dirs = runCatching {
                lanFilesRepository.listDirectory(state.connectionId, state.currentPath)
                    .first()
                    .filter { it.isFolder }
            }.getOrDefault(emptyList())
            val current = _lanPickerState.value ?: return@launch
            if (current.connectionId == state.connectionId && current.currentPath == state.currentPath) {
                _lanPickerState.value = current.copy(dirs = dirs, isLoading = false)
            }
        }
    }

    fun defaultLanPath(connectionId: Long): String =
        if (source is ArchiveSource.Lan && source.connectionId == connectionId)
            source.path.substringBeforeLast('/', "")
        else ""

    private fun directChildren(dir: String, all: List<ArchiveEntry>): List<ArchiveEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val seen = mutableSetOf<String>()
        val result = mutableListOf<ArchiveEntry>()

        for (entry in all) {
            val cleanPath = entry.path.trimEnd('/')
            if (!cleanPath.startsWith(prefix)) continue
            val relative = cleanPath.removePrefix(prefix)
            if (relative.isEmpty()) continue

            val firstComponent = relative.substringBefore('/')
            if (firstComponent in seen) continue
            seen.add(firstComponent)

            if (relative.contains('/')) {
                // This entry is deeper — add a synthetic directory for firstComponent
                val childPath = (if (prefix.isEmpty()) "" else prefix) + firstComponent
                result.add(ArchiveEntry(childPath, firstComponent, true, 0L, 0L))
            } else {
                result.add(entry)
            }
        }

        return result.sortedWith(
            compareByDescending<ArchiveEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }
}
