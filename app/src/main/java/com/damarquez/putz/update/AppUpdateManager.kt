package com.damarquez.putz.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.damarquez.putz.BuildConfig
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.data.transport.LanDaemonTransport
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// CONTRACT: self-update — LAN-first (the sidekick daemon, deployed as a Docker container on
// the NAS, serves the live NAS-delivered build over /api/apps/putz/{version,apk} — see
// http_server.py), falling back to the existing Google Drive apk.outputDir folder when LAN is
// disabled/unreachable. Mirrors SmartDaemonTransport's "try LAN, fall through to Drive" idiom.
private const val UPDATE_FOLDER_NAME = "Super Clipboard"
private const val UPDATE_APK_NAME = "putz.apk" // matches putz/gradle.properties apk.outputName
private const val APP_NAME = "putz" // matches the daemon's /api/apps/<app_name>/* routes
private const val RETRY_ATTEMPTS = 4

enum class UpdateSource { LAN, DRIVE }

sealed class UpdateCheckResult {
    data class UpdateAvailable(val source: UpdateSource, val remoteVersion: Long) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gDriveManager: GDriveManager,
    private val lanDaemonTransport: LanDaemonTransport,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun checkForUpdate(accountName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (settingsRepository.lanEnabledFlow.first() && lanDaemonTransport.isReachable()) {
            val lanVersion = lanDaemonTransport.getAppVersion(APP_NAME)
            if (lanVersion != null) {
                return@withContext if (lanVersion > BuildConfig.VERSION_CODE) {
                    UpdateCheckResult.UpdateAvailable(UpdateSource.LAN, lanVersion)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
            // LAN reachable but the daemon had nothing to report (e.g. no build delivered to
            // the NAS yet) — fall through to Drive rather than erroring out.
        }
        checkForUpdateViaDrive(accountName)
    }

    private suspend fun checkForUpdateViaDrive(accountName: String): UpdateCheckResult {
        return try {
            val service = gDriveManager.getDriveService(accountName)
            val folderId = retryWhile({ it == null }) { gDriveManager.findFolder(service, UPDATE_FOLDER_NAME, "root") }
                ?: return UpdateCheckResult.Error("Drive folder '$UPDATE_FOLDER_NAME' not found")
            // CONTRACT: self-update — reads the exact versionCode the build stamped into
            // BuildConfig (a sidecar text file next to the APK, synced to Drive alongside it),
            // not the APK's Drive modifiedTime. modifiedTime reflects when Drive Desktop finished
            // uploading the file — always later than the configuration-time timestamp baked into
            // versionCode — which made even the just-installed build look "newer than itself".
            val versionFile = retryWhile({ it == null }) { gDriveManager.findFileInFolder(service, folderId, "$UPDATE_APK_NAME.version") }
                ?: return UpdateCheckResult.Error("$UPDATE_APK_NAME.version not found in Drive")
            val remoteVersion = gDriveManager.downloadFileContent(accountName, versionFile.id)?.trim()?.toLongOrNull()
                ?: return UpdateCheckResult.Error("Couldn't read Drive version file")
            if (remoteVersion > BuildConfig.VERSION_CODE) {
                UpdateCheckResult.UpdateAvailable(UpdateSource.DRIVE, remoteVersion)
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Unknown error checking for updates")
        }
    }

    /** Downloads the update APK found by [checkForUpdate], from whichever source it reported. */
    suspend fun downloadUpdate(source: UpdateSource, accountName: String): File? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(updatesDir, UPDATE_APK_NAME)
        val downloaded = when (source) {
            UpdateSource.LAN ->
                retryWhile({ !it }) { lanDaemonTransport.downloadAppApk(APP_NAME, destination) }
            UpdateSource.DRIVE -> {
                // CONTRACT: self-update — putz.apk is ~100MB; a brief network hiccup mid-transfer
                // shouldn't surface as a hard failure, so retry the download specifically.
                val service = gDriveManager.getDriveService(accountName)
                val folderId = gDriveManager.findFolder(service, UPDATE_FOLDER_NAME, "root") ?: return@withContext null
                val remoteFile = gDriveManager.findFileInFolder(service, folderId, UPDATE_APK_NAME) ?: return@withContext null
                retryWhile({ !it }) { gDriveManager.downloadFileToDisk(accountName, remoteFile.id, destination) }
            }
        }
        if (!downloaded) return@withContext null
        if (parsePackageInfo(destination) == null) {
            destination.delete()
            return@withContext null
        }
        destination
    }

    // GDriveManager's and LanDaemonTransport's download/lookup methods already catch their own
    // exceptions and report failure as null/false rather than throwing, so retrying here is
    // driven by the result value, not by catching a thrown error.
    private suspend fun <T> retryWhile(shouldRetry: (T) -> Boolean, block: suspend () -> T): T {
        var result = block()
        var attempt = 1
        while (shouldRetry(result) && attempt < RETRY_ATTEMPTS) {
            delay(attempt * 2_000L)
            result = block()
            attempt++
        }
        return result
    }

    private fun parsePackageInfo(apkFile: File): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apkFile.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.path, 0)
        }
    }

    // CONTRACT: self-update — called once at app startup. Returns a one-time "Updated to X"
    // message on the first launch after a self-update actually installed a newer build; null on
    // every other launch (including the very first launch ever, so a fresh install stays quiet).
    suspend fun checkVersionAnnouncement(): String? {
        val lastAnnounced = settingsRepository.lastAnnouncedVersionCodeFlow.first()
        if (lastAnnounced == BuildConfig.VERSION_CODE) return null
        settingsRepository.saveLastAnnouncedVersionCode(BuildConfig.VERSION_CODE)
        if (lastAnnounced == 0) return null // fresh install — nothing to announce
        return "Updated to version ${BuildConfig.VERSION_NAME}"
    }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun buildInstallIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "com.damarquez.putz.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
