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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// CONTRACT: self-update — reuses the Drive folder both Putz and calibreAnywhere's Gradle
// builds already write their debug APK into (apk.outputDir in local.properties), so no new
// daemon-hosted delivery endpoint is needed. See CONTRACTS.md "self-update".
private const val UPDATE_FOLDER_NAME = "Super Clipboard"
private const val UPDATE_APK_NAME = "putz.apk" // matches putz/gradle.properties apk.outputName

sealed class UpdateCheckResult {
    data class UpdateAvailable(val apkFile: File, val versionCode: Long, val versionName: String?) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gDriveManager: GDriveManager,
) {
    suspend fun checkForUpdate(accountName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val service = gDriveManager.getDriveService(accountName)
            val folderId = gDriveManager.findFolder(service, UPDATE_FOLDER_NAME, "root")
                ?: return@withContext UpdateCheckResult.Error("Drive folder '$UPDATE_FOLDER_NAME' not found")
            val remoteFile = gDriveManager.findFileInFolder(service, folderId, UPDATE_APK_NAME)
                ?: return@withContext UpdateCheckResult.Error("$UPDATE_APK_NAME not found in Drive")

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val destination = File(updatesDir, UPDATE_APK_NAME)
            val downloaded = gDriveManager.downloadFileToDisk(accountName, remoteFile.id, destination)
            if (!downloaded) return@withContext UpdateCheckResult.Error("Failed to download update")

            val info = parsePackageInfo(destination)
                ?: return@withContext UpdateCheckResult.Error("Downloaded file is not a valid APK")
            val remoteVersionCode = info.longVersionCode
            if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                destination.delete()
                return@withContext UpdateCheckResult.UpToDate
            }
            UpdateCheckResult.UpdateAvailable(destination, remoteVersionCode, info.versionName)
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Unknown error checking for updates")
        }
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
