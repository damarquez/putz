package com.damarquez.putz.ui.files

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.model.AccountInfo
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.FilesRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class FilesUiState {
    data object Loading : FilesUiState()
    data class Success(
        val files: List<PutioFile>,
        val parent: PutioFile?,
        val isRefreshing: Boolean = false,
        val searchResults: List<PutioFile>? = null,
        val isSearching: Boolean = false,
        val isScanning: Boolean = false,
    ) : FilesUiState()
    data class Error(val message: String) : FilesUiState()
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val filesRepository: FilesRepository,
    private val localFilesRepository: com.damarquez.putz.data.repository.LocalFilesRepository,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val parentId: Long = savedStateHandle[Screen.Files.ARG_PARENT_ID] ?: 0L
    val folderName: String = savedStateHandle[Screen.Files.ARG_FOLDER_NAME] ?: "Your Files"
    val highlightFileId: Long = savedStateHandle[Screen.Files.ARG_HIGHLIGHT_ID] ?: -1L
    val localUri: String? = savedStateHandle[Screen.Files.ARG_LOCAL_URI]

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isSearchMode = MutableStateFlow(false)
    val isSearchMode: StateFlow<Boolean> = _isSearchMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadFiles()
        if (parentId == 0L) loadAccountInfo()
    }

    fun loadFiles(isRefresh: Boolean = false) {
        if (_isSearchMode.value) {
            search(_searchQuery.value)
            return
        }
        viewModelScope.launch {
            val isLocalRoot = parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID
            val isLocalFolder = localUri != null || parentId <= com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_FOLDER_PREFIX_ID - 1000

            if (isLocalRoot) {
                // Browsing the Local Files virtual root
                _uiState.value = if (isRefresh) (uiState.value as? FilesUiState.Success)?.copy(isRefreshing = true) ?: FilesUiState.Loading else FilesUiState.Loading
                val attachments = localFilesRepository.getAttachments()
                _uiState.value = FilesUiState.Success(files = attachments, parent = null)
                return@launch
            }

            if (isLocalFolder && localUri != null) {
                // Browsing inside a local folder - STREAMED
                _uiState.value = if (isRefresh) (uiState.value as? FilesUiState.Success)?.copy(isRefreshing = true) ?: FilesUiState.Loading else FilesUiState.Loading
                localFilesRepository.listLocalFolder(localUri).collect { files ->
                    _uiState.value = FilesUiState.Success(
                        files = files,
                        parent = null,
                        isRefreshing = false,
                        isScanning = true
                    )
                }
                // Once collect finishes, scanning is done
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    _uiState.value = current.copy(isScanning = false)
                }
                return@launch
            }

            // Normal put.io loading
            if (!isRefresh) {
                val cached = filesRepository.getCached(parentId)
                if (cached != null) {
                    val (files, parent) = cached
                    _uiState.value = FilesUiState.Success(
                        files = augmentWithLocal(files),
                        parent = parent,
                    )
                    return@launch
                }
                _uiState.value = FilesUiState.Loading
            } else {
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    _uiState.value = current.copy(isRefreshing = true)
                }
            }

            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.listFiles(token, parentId)) {
                is NetworkResult.Success -> {
                    val (files, parent) = result.data
                    _uiState.value = FilesUiState.Success(files = augmentWithLocal(files), parent = parent)
                }
                is NetworkResult.Error -> {
                    _uiState.value = FilesUiState.Error(result.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun augmentWithLocal(apiFiles: List<PutioFile>): List<PutioFile> {
        val list = if (parentId == 0L) {
            val localRoot = PutioFile(
                id = com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID,
                name = "Local Files",
                fileType = "FOLDER",
                isLocal = true
            )
            listOf(localRoot) + apiFiles
        } else apiFiles

        return list.sortedWith(
            compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() }
        )
    }

    fun attachLocal(uri: android.net.Uri, name: String, isFolder: Boolean) {
        viewModelScope.launch {
            localFilesRepository.attach(uri, name, isFolder)
            if (parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID) {
                loadFiles(isRefresh = true)
            }
        }
    }

    fun sendToCalibre(file: PutioFile, title: String, author: String, archiveMode: String? = null) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            
            var targetFileId = file.id
            var isTempUpload = false

            if (file.isLocal && file.localUri != null) {
                // Phase 3: Handle local file upload
                _snackbarMessage.value = "Uploading \"${file.name}\" to put.io..."
                
                // 1. Find or create .putz_attachments
                val rootFiles = filesRepository.listFiles(putioToken, 0).dataOrNull()?.first ?: emptyList()
                var tempFolderId = rootFiles.find { it.name == ".putz_attachments" && it.isFolder }?.id
                
                if (tempFolderId == null) {
                    val createResult = filesRepository.createFolder(putioToken, 0, ".putz_attachments")
                    if (createResult is NetworkResult.Success) {
                        tempFolderId = createResult.data.id
                    } else {
                        _snackbarMessage.value = "Failed to create temp folder: ${(createResult as NetworkResult.Error).message}"
                        return@launch
                    }
                }

                // 2. Upload the file
                val uploadResult = filesRepository.uploadFile(
                    putioToken, 
                    tempFolderId!!, 
                    file.name, 
                    android.net.Uri.parse(file.localUri), 
                    context.contentResolver
                )

                if (uploadResult is NetworkResult.Success) {
                    targetFileId = uploadResult.data.id
                    isTempUpload = true
                } else {
                    _snackbarMessage.value = "Upload failed: ${(uploadResult as NetworkResult.Error).message}"
                    return@launch
                }
            }

            val downloadUrl = filesRepository.getDownloadUrl(putioToken, targetFileId)

            calibreRepository.addTransfer(
                putioFileId = targetFileId,
                fileName = file.name,
                title = title,
                author = author,
                googleAccount = googleAccount,
                downloadUrl = downloadUrl,
                archiveMode = archiveMode,
                isTempUpload = isTempUpload,
                sourceLocalUri = file.localUri,
            )
            _snackbarMessage.value = "Transfer requested for $title"
        }
    }

    private fun <T> NetworkResult<T>.dataOrNull(): T? = (this as? NetworkResult.Success)?.data

    fun sendAudiobookPack(files: List<PutioFile>, title: String, author: String) {
        viewModelScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                _snackbarMessage.value = "Link your Google account in Settings first"
                return@launch
            }

            val putioToken = settingsRepository.authTokenFlow.first()
            val filesWithUrls = files.map { file ->
                file to filesRepository.getDownloadUrl(putioToken, file.id)
            }

            calibreRepository.addAudiobookPackTransfer(
                files = filesWithUrls,
                title = title,
                author = author,
                googleAccount = googleAccount,
            )
            _snackbarMessage.value = "Audiobook transfer requested for $title"
        }
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    suspend fun checkBookExists(title: String, author: String): Boolean {
        val dbFile = File(context.filesDir, "metadata.db")
        return calibreRepository.checkExists(dbFile, title, author)
    }

    private fun loadAccountInfo() {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            val result = filesRepository.getAccountInfo(token)
            if (result is NetworkResult.Success) {
                _accountInfo.value = result.data
            }
        }
    }

    fun deleteFiles(files: List<PutioFile>) {
        viewModelScope.launch {
            val localFiles = files.filter { it.isLocal }
            val remoteIds = files.filter { !it.isLocal }.map { it.id }

            if (localFiles.isNotEmpty()) {
                localFiles.forEach { local ->
                    if (local.localUri != null) {
                        localFilesRepository.detachOrHide(local.localUri)
                    } else {
                        localFilesRepository.detach(local.id)
                    }
                }
                _snackbarMessage.value = if (localFiles.size == 1) "Detached" else "${localFiles.size} items detached"
                loadFiles(isRefresh = true)
            }

            if (remoteIds.isNotEmpty()) {
                val token = settingsRepository.authTokenFlow.first()
                when (val result = filesRepository.deleteFiles(token, remoteIds)) {
                    is NetworkResult.Success -> {
                        _snackbarMessage.value = if (remoteIds.size == 1) "Deleted" else "${remoteIds.size} items deleted"
                        loadFiles(isRefresh = true)
                    }
                    is NetworkResult.Error -> {
                        _snackbarMessage.value = "Delete failed: ${result.message}"
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    fun toggleSearch() {
        _isSearchMode.value = !_isSearchMode.value
        if (!_isSearchMode.value) {
            _searchQuery.value = ""
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(searchResults = null, isSearching = false)
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        search(query)
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(searchResults = null, isSearching = false)
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(300) // Debounce
            
            val isLocalRoot = parentId == com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_ROOT_ID
            val isLocalFolder = localUri != null || parentId <= com.damarquez.putz.data.repository.LocalFilesRepository.LOCAL_FOLDER_PREFIX_ID - 1000

            if (isLocalRoot || isLocalFolder) {
                // LOCAL SEARCH - STREAMED
                val current = _uiState.value
                if (current is FilesUiState.Success) {
                    // Instant shallow filter of already loaded files
                    val immediateResults = current.files.filter { it.name.contains(query, ignoreCase = true) }
                    _uiState.value = current.copy(
                        isSearching = true, 
                        searchResults = immediateResults
                    )
                }

                localFilesRepository.searchLocalFiles(query, localUri).collect { results ->
                    val cur = _uiState.value
                    if (cur is FilesUiState.Success) {
                        // Merge immediate results with background scan results (Set removes duplicates)
                        val merged = (cur.searchResults.orEmpty() + results).distinctBy { it.localUri ?: it.id }
                        _uiState.value = cur.copy(searchResults = merged, isSearching = true)
                    }
                }
                
                val curFinal = _uiState.value
                if (curFinal is FilesUiState.Success) {
                    _uiState.value = curFinal.copy(isSearching = false)
                }
                return@launch
            }

            // CLOUD SEARCH
            val current = _uiState.value
            if (current is FilesUiState.Success) {
                _uiState.value = current.copy(isSearching = true)
            }

            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.searchFiles(token, query, parentId)) {
                is NetworkResult.Success -> {
                    val currentSuccess = _uiState.value as? FilesUiState.Success
                    if (currentSuccess != null) {
                        _uiState.value = currentSuccess.copy(
                            searchResults = result.data.sortedBy { it.name.lowercase() },
                            isSearching = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    // Handle error, maybe show snackbar
                    val currentSuccess = _uiState.value as? FilesUiState.Success
                    if (currentSuccess != null) {
                        _uiState.value = currentSuccess.copy(isSearching = false)
                    }
                    _snackbarMessage.value = "Search failed: ${result.message}"
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun signOut() = settingsRepository.clearAuth()

    fun onHighlightHandled() {
        // We can't easily modify SavedStateHandle once read, 
        // but the Screen can use this to stop the highlighting effect.
    }
}
