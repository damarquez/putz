package com.damarquez.putz.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlobalSyncViewModel @Inject constructor(
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val libraryHasUpdates: StateFlow<Boolean> = settingsRepository.libraryHasUpdatesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                val account = settingsRepository.googleTokenFlow.first()
                if (account.isNotBlank()) {
                    try {
                        calibreRepository.pollLibraryUpdates(account)
                        calibreRepository.pollHeartbeat(account)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                delay(60_000) // Poll every 60 seconds
            }
        }
    }
}
