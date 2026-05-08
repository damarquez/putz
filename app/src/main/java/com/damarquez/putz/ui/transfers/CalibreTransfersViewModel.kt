package com.damarquez.putz.ui.transfers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.data.local.CalibreTransferEntity
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CalibreTransfersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val transfers: StateFlow<List<CalibreTransferEntity>> = calibreRepository.getTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                val account = settingsRepository.googleTokenFlow.first()
                if (account.isNotBlank()) {
                    calibreRepository.pollResponses(account)

                    // Also check for stuck transfers (older than 5 mins)
                    val currentTransfers = calibreRepository.getTransfers().first()
                    val now = System.currentTimeMillis()
                    currentTransfers.forEach { transfer ->
                        if ((transfer.status == com.damarquez.putz.data.local.CalibreTransferStatus.REQUESTED ||
                                    transfer.status == com.damarquez.putz.data.local.CalibreTransferStatus.PROCESSING) &&
                            now - transfer.lastUpdatedAt > 5 * 60 * 1000) {
                            calibreRepository.sendProbeRequest(transfer.putioFileId, account)
                        }
                    }
                }
                delay(10_000) // Poll every 10 seconds
            }
        }
    }

    fun probeTransfer(fileId: Long) {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isNotBlank()) {
                calibreRepository.sendProbeRequest(fileId, account)
            }
        }
    }

    fun syncMetadata() {
        viewModelScope.launch {
            val account = settingsRepository.googleTokenFlow.first()
            if (account.isNotBlank()) {
                val dbFile = File(context.filesDir, "metadata.db")
                calibreRepository.syncMetadataDb(account, dbFile)
            }
        }
    }

    fun removeTransfer(fileId: Long) {
        viewModelScope.launch {
            calibreRepository.removeTransfer(fileId)
        }
    }

    fun deleteFromPutio(fileId: Long) {
        viewModelScope.launch {
            val token = settingsRepository.authTokenFlow.first()
            calibreRepository.deleteFileFromPutio(token, fileId)
            // After successful delete from put.io, we can also remove from local list
            calibreRepository.removeTransfer(fileId)
        }
    }
}
