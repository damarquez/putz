package com.damarquez.putz.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.damarquez.putz.MainActivity
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.LocalFilesRepository
import com.damarquez.putz.data.repository.TransferDeleteProgress
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Runs the batch-clear-verified-transfers job itself (not just its notification). It used to
 * only show a notification while [com.damarquez.putz.ui.transfers.CalibreTransfersViewModel]
 * ran the delete loop on viewModelScope — but that scope is tied to the CalibreTransfers screen's
 * NavBackStackEntry, so navigating away popped the destination, cleared the ViewModelStore, and
 * killed the loop mid-delete despite the notification still showing. The service now owns the
 * loop on its own scope, which only ends when the job finishes (or the service is explicitly
 * stopped), so it survives navigation and the ViewModel being cleared.
 */
@AndroidEntryPoint
class TransferDeleteService : Service() {

    @Inject
    lateinit var calibreRepository: CalibreRepository

    @Inject
    lateinit var localFilesRepository: LocalFilesRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        serviceScope.launch {
            calibreRepository.deleteProgress.sample(500L).collectLatest { progress ->
                if (progress != null) {
                    getNotificationManager().notify(NOTIFICATION_ID, buildNotification(progress.message))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileIds = intent?.getLongArrayExtra(EXTRA_FILE_IDS)
        val alsoDeleteFromPutio = intent?.getBooleanExtra(EXTRA_ALSO_DELETE, false) ?: false

        startForeground(NOTIFICATION_ID, buildNotification("Preparing to delete transfers..."))

        if (fileIds == null || fileIds.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                clearGreenTransfers(fileIds.toList(), alsoDeleteFromPutio)
            } finally {
                calibreRepository.updateDeleteProgress(null)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun clearGreenTransfers(fileIds: List<Long>, alsoDeleteFromPutio: Boolean) {
        val green = calibreRepository.getTransfers().first().filter { it.putioFileId in fileIds }
        if (green.isEmpty()) return
        val token = if (alsoDeleteFromPutio) settingsRepository.authTokenFlow.first() else ""

        var failedCount = 0
        green.forEachIndexed { index, transfer ->
            var failureMessage: String? = null
            if (alsoDeleteFromPutio && transfer.hasPutioFile) {
                if (transfer.isTempUpload && transfer.sourceLocalUri != null) {
                    calibreRepository.updateDeleteProgress(
                        TransferDeleteProgress(
                            message = "Detaching local file (transfer ${index + 1}/${green.size})",
                            current = index + 1,
                            total = green.size,
                        )
                    )
                    localFilesRepository.detachOrHide(transfer.sourceLocalUri)
                } else {
                    val fileCount = transfer.parsedFileIds().size
                    calibreRepository.updateDeleteProgress(
                        TransferDeleteProgress(
                            message = "Deleting $fileCount file${if (fileCount == 1) "" else "s"} " +
                                "(transfer ${index + 1}/${green.size})",
                            current = index + 1,
                            total = green.size,
                        )
                    )
                    val result = calibreRepository.deleteFileFromPutio(token, transfer.putioFileId)
                    if (result is NetworkResult.Error) {
                        failureMessage = result.message
                        failedCount++
                        android.util.Log.w("TransferDeleteService",
                            "Failed to delete put.io files for transfer ${transfer.putioFileId}: ${result.message}")
                    }
                }
            }
            // Only drop the local record once the put.io delete actually succeeded —
            // otherwise the transfer would vanish from the list with no way to retry it.
            if (failureMessage == null) {
                calibreRepository.removeTransfer(transfer.putioFileId)
            } else {
                // Surface the failure on the transfer itself (CalibreTransferItem already renders
                // errorMessage for any status) — the service has no screen to show a Snackbar on,
                // and this survives the app being closed/reopened, unlike the notification below.
                calibreRepository.setTransferErrorMessage(transfer.putioFileId, "Delete failed: $failureMessage")
            }
        }

        val clearedCount = green.size - failedCount
        val summary = if (failedCount == 0) {
            "$clearedCount transfer${if (clearedCount == 1) "" else "s"} cleared"
        } else {
            "$clearedCount cleared, $failedCount failed to delete from put.io — see the transfer list"
        }
        // Detach from the foreground state instead of just re-notifying: once this service calls
        // stopSelf(), Android removes any notification still tied to startForeground(), so without
        // detaching, the summary (especially a failure count) would flash and disappear before
        // it could be read.
        stopForeground(STOP_FOREGROUND_DETACH)
        getNotificationManager().notify(NOTIFICATION_ID, buildNotification(summary, ongoing = false))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getNotificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Putz is clearing transfers")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Transfer deletion", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while Putz deletes cleared transfers and their put.io files"
            }
            getNotificationManager().createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "transfer_delete"
        private const val NOTIFICATION_ID = 4301
        private const val EXTRA_FILE_IDS = "file_ids"
        private const val EXTRA_ALSO_DELETE = "also_delete_from_putio"

        fun start(context: Context, fileIds: List<Long>, alsoDeleteFromPutio: Boolean) {
            val intent = Intent(context, TransferDeleteService::class.java)
                .putExtra(EXTRA_FILE_IDS, fileIds.toLongArray())
                .putExtra(EXTRA_ALSO_DELETE, alsoDeleteFromPutio)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
