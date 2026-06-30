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
 * Keeps the process foregrounded while a "send to Calibre" pack (e.g. CBR -> PDF) is being
 * resolved and dispatched, before its transfer row exists in the database. Without this,
 * Android can kill the work outright as soon as the screen locks or the app is backgrounded
 * (it's just a plain coroutine at that point, with nothing persisted yet), and the operation
 * vanishes without a trace and without ever appearing in the Calibre Transfers list.
 *
 * Lifecycle is owned by [com.damarquez.putz.ui.files.FilesViewModel] — it calls [start] right
 * before resolution begins and [stop] once the transfer row has been created and dispatched.
 */
@AndroidEntryPoint
class TransferPrepareService : Service() {

    @Inject
    lateinit var calibreRepository: CalibreRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing files..."))

        serviceScope.launch {
            calibreRepository.prepareProgress.sample(500L).collectLatest { progress ->
                val text = if (progress != null) "Resolving file ${progress.first}/${progress.second}" else "Sending to Calibre..."
                getNotificationManager().notify(NOTIFICATION_ID, buildNotification(text))
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
            .setContentTitle("Putz is sending a file to Calibre")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Transfer preparation", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while Putz prepares a file to send to Calibre"
            }
            getNotificationManager().createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "transfer_prepare"
        private const val NOTIFICATION_ID = 4302

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferPrepareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferPrepareService::class.java))
        }
    }
}
