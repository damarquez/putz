package com.damarquez.putz.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.damarquez.putz.data.local.CalibreTransferStatus
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

    val googleAccount: StateFlow<String> = settingsRepository.googleTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                val account = settingsRepository.googleTokenFlow.first()
                if (account.isNotBlank()) {
                    try {
                        calibreRepository.pollResponses(account)
                        calibreRepository.pollLibraryUpdates(account)
                        calibreRepository.pollHeartbeat(account)
                        calibreRepository.refreshDriveRequestCount(account)
                    } catch (e: Exception) {
                        android.util.Log.e("GlobalSyncViewModel", "Poll cycle failed", e)
                    }

                    val now = System.currentTimeMillis()
                    val activeProgressKeys = calibreRepository.uploadProgress.value.keys
                    val progressTimestamps = calibreRepository.uploadProgressTimestamp.value
                    calibreRepository.getTransfers().first().forEach { transfer ->
                        val progressTs = progressTimestamps[transfer.putioFileId]
                        // Restart UPLOADING if: no progress key and idle >60s (original orphan case)
                        // OR: progress key present but not updated in >4 min (stuck in retry loop)
                        val isOrphanedUpload = transfer.status == CalibreTransferStatus.UPLOADING && (
                            (transfer.putioFileId !in activeProgressKeys && now - transfer.lastUpdatedAt > 60_000) ||
                            (progressTs != null && now - progressTs > 4 * 60_000)
                        )
                        // Also restart FAILED transfers that still have local URIs — these were
                        // pack uploads that exhausted all retries but can be retried from scratch.
                        val isRetryableFailed = transfer.status == CalibreTransferStatus.FAILED &&
                            !transfer.localUrisJson.isNullOrBlank() &&
                            transfer.putioFileId !in activeProgressKeys &&
                            now - transfer.lastUpdatedAt > 5 * 60_000
                        if (isOrphanedUpload || isRetryableFailed) {
                            launch { calibreRepository.restartOrphanedUpload(transfer) }
                        }
                    }
                }
                val interval = if (settingsRepository.lanEnabledFlow.first()) 10_000L else 60_000L
                delay(interval)
            }
        }
    }
}
