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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    // Tracked so it can be cancelled before stopForeground below — otherwise its sample(500L)
    // ticker can fire a stale, buffered progress value right around teardown and repost a fresh
    // ongoing notification that races the service's own destruction, sometimes leaving a
    // non-dismissable notification behind with nothing left to remove it (this was the "Putz is
    // sending a file to Calibre" notification getting stuck after the send finished).
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing files..."))

        progressJob = serviceScope.launch {
            calibreRepository.prepareProgress.sample(500L).collectLatest { progress ->
                val text = if (progress != null) "Resolving file ${progress.first}/${progress.second}" else "Working..."
                getNotificationManager().notify(NOTIFICATION_ID, buildNotification(text))
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        progressJob?.cancel()
        // Explicitly remove the notification here rather than relying on the implicit cleanup
        // Android normally does when a foreground service stops — that implicit path is exactly
        // what raced against the stale progress emission above.
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            .setContentTitle("Putz is working")
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

        // Callers overlap (concurrent sends from different screens can each bracket their own
        // work with start()/stop()) and, more importantly, a "preparation" that finishes
        // essentially instantly (nothing to resolve) can call stop() right on the heels of
        // start(). Android arms its "must call startForeground within 5s" timer the instant
        // startForegroundService() is called, and a stopService() that lands before this
        // service's own onStartCommand()/startForeground() has actually run doesn't reliably
        // cancel that timer — so the OS can still kill the process with
        // ForegroundServiceDidNotStartInTimeException even though the code goes on to call
        // startForeground() correctly, just a beat too late for an instance already being torn
        // down. Ref-count start/stop pairs and debounce the real stopService() call so a quick
        // start-then-stop can't race the service's own startup, and an overlapping caller's stop
        // can't tear down the service while another caller is still using it.
        private const val STOP_DEBOUNCE_MS = 1500L
        private val lock = Any()
        private var activeSessions = 0
        private var pendingStopJob: Job? = null
        private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun start(context: Context) {
            val shouldStart = synchronized(lock) {
                pendingStopJob?.cancel()
                pendingStopJob = null
                activeSessions++
                activeSessions == 1
            }
            if (shouldStart) {
                ContextCompat.startForegroundService(context, Intent(context, TransferPrepareService::class.java))
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            synchronized(lock) {
                activeSessions = (activeSessions - 1).coerceAtLeast(0)
                if (activeSessions == 0) {
                    pendingStopJob?.cancel()
                    pendingStopJob = stopScope.launch {
                        delay(STOP_DEBOUNCE_MS)
                        synchronized(lock) {
                            if (activeSessions == 0) {
                                appContext.stopService(Intent(appContext, TransferPrepareService::class.java))
                            }
                        }
                    }
                }
            }
        }
    }
}
