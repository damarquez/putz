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
import com.damarquez.putz.data.repository.CalibreRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process foregrounded (with a persistent notification) while batch-deleting
 * calibre transfers and their put.io files, so Android doesn't freeze/throttle the work
 * once the app is backgrounded, and so the user can see what's happening without keeping
 * the screen open.
 *
 * Lifecycle is owned by [com.damarquez.putz.ui.transfers.CalibreTransfersViewModel] — it calls
 * [start] right before the delete loop begins and [stop] in a finally block once it ends.
 */
@AndroidEntryPoint
class TransferDeleteService : Service() {

    @Inject
    lateinit var calibreRepository: CalibreRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing to delete transfers..."))

        serviceScope.launch {
            calibreRepository.deleteProgress.sample(500L).collectLatest { progress ->
                if (progress != null) {
                    getNotificationManager().notify(NOTIFICATION_ID, buildNotification(progress.message))
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getNotificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Putz is clearing transfers")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferDeleteService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferDeleteService::class.java))
        }
    }
}
