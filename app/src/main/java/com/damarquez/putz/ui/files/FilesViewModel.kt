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
    ) : FilesUiState()
    data class Error(val message: String) : FilesUiState()
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val filesRepository: FilesRepository,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val parentId: Long = savedStateHandle[Screen.Files.ARG_PARENT_ID] ?: 0L
    val folderName: String = savedStateHandle[Screen.Files.ARG_FOLDER_NAME] ?: "Your Files"

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        loadFiles()
        if (parentId == 0L) loadAccountInfo()
    }

    fun loadFiles(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (!isRefresh) {
                val cached = filesRepository.getCached(parentId)
                if (cached != null) {
                    val (files, parent) = cached
                    _uiState.value = FilesUiState.Success(
                        files = files.sortedWith(
                            compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() }
                        ),
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
                    val sorted = files.sortedWith(
                        compareByDescending<PutioFile> { it.isFolder }
                            .thenBy { it.name.lowercase() }
                    )
                    _uiState.value = FilesUiState.Success(files = sorted, parent = parent)
                }
                is NetworkResult.Error -> {
                    _uiState.value = FilesUiState.Error(result.message)
                }
                NetworkResult.Loading -> Unit
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
            val downloadUrl = filesRepository.getDownloadUrl(putioToken, file.id)

            calibreRepository.addTransfer(
                putioFileId = file.id,
                fileName = file.name,
                title = title,
                author = author,
                googleAccount = googleAccount,
                downloadUrl = downloadUrl,
                archiveMode = archiveMode,
            )
            _snackbarMessage.value = "Transfer requested for $title"
        }
    }

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

    fun deleteFiles(ids: List<Long>) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            when (val result = filesRepository.deleteFiles(token, ids)) {
                is NetworkResult.Success -> {
                    _snackbarMessage.value = if (ids.size == 1) "Deleted" else "${ids.size} items deleted"
                    loadFiles(isRefresh = true)
                }
                is NetworkResult.Error -> {
                    _snackbarMessage.value = "Delete failed: ${result.message}"
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun signOut() = settingsRepository.clearAuth()
}
