package com.damarquez.putz.data.remote

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GDriveManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val scopes = listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_METADATA_READONLY)

    // Keyed by account name so switching Google accounts still gets its own client. Building a
    // Drive service spins up its own credential + HTTP client machinery; pollResponses fetches a
    // backlog of response files in 20-way concurrent bursts (GDriveDaemonTransport.pollResponses),
    // and each of those calls used to rebuild a brand-new service from scratch — a large backlog
    // meant dozens of these piling up simultaneously, which was observed driving the app to a
    // native OOM crash. Caching means concurrent calls for the same account reuse one instance.
    private val driveServiceCache = java.util.concurrent.ConcurrentHashMap<String, Drive>()

    internal fun getDriveService(accountName: String): Drive =
        driveServiceCache.computeIfAbsent(accountName, ::buildDriveService)

    private fun buildDriveService(accountName: String): Drive {
        Log.d("GDriveManager", "Creating Drive service for account: '$accountName'")

        val credential = GoogleAccountCredential.usingOAuth2(context, scopes)

        // 1. Try to get account from GoogleSignIn (best way as it's already authorized)
        val lastAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
        if (lastAccount?.email?.equals(accountName, ignoreCase = true) == true) {
            val account = lastAccount.account
            if (account != null) {
                Log.d("GDriveManager", "Found matching Account via GoogleSignIn: ${account.name}")
                credential.selectedAccount = account
                return Drive.Builder(transport, jsonFactory, credential)
                    .setApplicationName("Putz")
                    .build()
            }
        }

        // 2. Fallback to finding the actual Account object in AccountManager
        val accountManager = android.accounts.AccountManager.get(context)
        val googleAccounts = try {
            accountManager.getAccountsByType("com.google")
        } catch (e: Exception) {
            Log.e("GDriveManager", "Failed to list accounts", e)
            emptyArray<android.accounts.Account>()
        }

        val matchingAccount = googleAccounts.find { it.name.equals(accountName, ignoreCase = true) }

        if (matchingAccount != null) {
            Log.d("GDriveManager", "Found matching Account object via AccountManager: ${matchingAccount.name}")
            credential.selectedAccount = matchingAccount
        } else {
            Log.w("GDriveManager", "No matching Account object found for '$accountName'.")
            Log.d("GDriveManager", "Available Google accounts: ${googleAccounts.joinToString { it.name }}")
            credential.selectedAccountName = accountName
        }

        return Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName("Putz")
            .build()
    }

    suspend fun findFolder(service: Drive, folderName: String, parentId: String? = "root"): String? = withContext(Dispatchers.IO) {
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false" +
                (if (parentId != null) " and '$parentId' in parents" else "")
        val result = service.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
        result.files.firstOrNull()?.id
    }

    suspend fun createFolder(service: Drive, folderName: String, parentId: String? = "root"): String = withContext(Dispatchers.IO) {
        val metadata = com.google.api.services.drive.model.File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) parents = listOf(parentId)
        }
        service.files().create(metadata).setFields("id").execute().id
    }

    internal suspend fun getLibraryFolderId(service: Drive): String? = withContext(Dispatchers.IO) {
        try {
            val libResult = service.files().list()
                .setQ("name = 'metadata.db' and trashed = false")
                .setFields("files(parents)")
                .execute()
            
            val files = libResult.files
            if (files.isNullOrEmpty()) {
                Log.e("GDriveManager", "metadata.db not found anywhere in Drive")
                return@withContext null
            }
            files.firstOrNull()?.parents?.firstOrNull()
        } catch (e: Exception) {
            Log.e("GDriveManager", "Could not resolve Calibre library root", e)
            throw e
        }
    }

    // CONTRACT: IPC transport
    suspend fun uploadRequest(accountName: String, fileName: String, content: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("GDriveManager", "Uploading request $fileName for $accountName")
            val service = getDriveService(accountName)
            
            val libFolderId = getLibraryFolderId(service) ?: run {
                Log.e("GDriveManager", "Could not find Calibre library root (metadata.db missing)")
                return@withContext null
            }

            val rootFolderId = findFolder(service, ".calibre_integration", libFolderId) 
                ?: createFolder(service, ".calibre_integration", libFolderId)
            
            val requestsFolderId = findFolder(service, "requests", rootFolderId) ?: createFolder(service, "requests", rootFolderId)

            // Drive allows multiple files with the same name in one folder — files().create()
            // never replaces an existing one. Without this, every retry for the same book piles
            // up another "req_<id>.json" alongside earlier (possibly broken/stale) copies, and
            // the daemon works through each one independently over several minutes, so an old
            // failure can keep resurfacing and overwriting a newer, already-fixed request's
            // result. Trash any existing copies for this exact filename before uploading the
            // new one so at most one live request per book/action survives at a time.
            try {
                val existing = service.files().list()
                    .setQ("name = '$fileName' and '$requestsFolderId' in parents and trashed = false")
                    .setFields("files(id)")
                    .execute()
                existing.files?.forEach { stale ->
                    Log.d("GDriveManager", "Trashing stale duplicate request $fileName (id=${stale.id}) before resubmitting")
                    service.files().update(stale.id, com.google.api.services.drive.model.File().apply { trashed = true }).execute()
                }
            } catch (e: Exception) {
                Log.w("GDriveManager", "Could not check for stale duplicate requests named $fileName", e)
            }

            val tempFile = File.createTempFile("upload_", "_$fileName", context.cacheDir)
                .apply { writeText(content) }
            val uploaded = try {
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = fileName
                    parents = listOf(requestsFolderId)
                }
                val mediaContent = FileContent("application/json", tempFile)
                service.files().create(metadata, mediaContent).setFields("id").execute()
            } finally {
                tempFile.delete()
            }
            Log.d("GDriveManager", "Upload successful, ID: ${uploaded.id}")
            uploaded.id
        } catch (e: Exception) {
            Log.e("GDriveManager", "Upload failed", e)
            null
        }
    }

    suspend fun getFileMetadata(accountName: String, fileName: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: return@withContext null
            val result = service.files().list()
                .setQ("name = '$fileName' and '$libFolderId' in parents and trashed = false")
                .setFields("files(id, modifiedTime)")
                .execute()
            
            val file = result.files.firstOrNull() ?: return@withContext null
            val modifiedTime = file.modifiedTime?.value ?: 0L
            file.id to modifiedTime
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadMetadataDb(accountName: String, destination: File): com.damarquez.putz.data.model.NetworkResult<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d("GDriveManager", "Downloading metadata.db for $accountName")
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: return@withContext com.damarquez.putz.data.model.NetworkResult.Error("Could not find Calibre library root (metadata.db missing in your Google Drive)")
            
            val result = service.files().list()
                .setQ("name = 'metadata.db' and '$libFolderId' in parents and trashed = false")
                .setFields("files(id, name)")
                .execute()
            
            val fileId = result.files.firstOrNull()?.id ?: return@withContext com.damarquez.putz.data.model.NetworkResult.Error("metadata.db not found in library folder")
            
            FileOutputStream(destination).use { output ->
                service.files().get(fileId).executeMediaAndDownloadTo(output)
            }
            Log.d("GDriveManager", "Download successful")
            com.damarquez.putz.data.model.NetworkResult.Success(Unit)
        } catch (e: Exception) {
            Log.e("GDriveManager", "Download failed", e)
            val message = when {
                e.message?.contains("name must not be empty", ignoreCase = true) == true -> "Google Account error: please try signing out and in again in Settings"
                e is com.google.android.gms.auth.UserRecoverableAuthException -> "Google Drive authorization required"
                else -> e.message ?: "Unknown Google Drive error"
            }
            com.damarquez.putz.data.model.NetworkResult.Error(message)
        }
    }

    // CONTRACT: IPC transport
    suspend fun listResponses(accountName: String, appId: String): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: run {
                Log.w("GDriveManager", "listResponses: could not resolve library folder (metadata.db not found)")
                return@withContext emptyList()
            }

            val rootId = findFolder(service, ".calibre_integration", libFolderId) ?: run {
                Log.w("GDriveManager", "listResponses: .calibre_integration folder not found under $libFolderId")
                return@withContext emptyList()
            }
            val responsesId = findFolder(service, "responses", rootId) ?: run {
                Log.w("GDriveManager", "listResponses: responses folder not found under $rootId")
                return@withContext emptyList()
            }
            val appFolderId = findFolder(service, appId, responsesId) ?: run {
                Log.w("GDriveManager", "listResponses: no subfolder named '$appId' under responses ($responsesId) — nothing to read")
                return@withContext emptyList()
            }

            val all = mutableListOf<com.google.api.services.drive.model.File>()
            var pageToken: String? = null
            do {
                val result = service.files().list()
                    .setQ("'$appFolderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name)")
                    .setOrderBy("name")
                    .setPageSize(1000)
                    .also { if (pageToken != null) it.pageToken = pageToken }
                    .execute()
                all += result.files ?: emptyList()
                pageToken = result.nextPageToken
            } while (pageToken != null)
            Log.d("GDriveManager", "listResponses: found ${all.size} file(s) in app folder $appFolderId (appId=$appId)")
            all
        } catch (e: Exception) {
            Log.e("GDriveManager", "listResponses: threw for appId=$appId", e)
            emptyList()
        }
    }

    suspend fun downloadFileContent(accountName: String, fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            service.files().get(fileId).executeMediaAsInputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("GDriveManager", "downloadFileContent: FAILED for fileId=$fileId", e)
            null
        }
    }

    suspend fun checkFileExists(accountName: String, fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val file = service.files().get(fileId).setFields("id, trashed").execute()
            file != null && !file.getTrashed()
        } catch (e: Exception) {
            false
        }
    }

    // CONTRACT: self-update — generic (not library-folder-scoped) lookup, unlike getFileMetadata.
    suspend fun findFileInFolder(service: Drive, folderId: String, fileName: String): com.google.api.services.drive.model.File? = withContext(Dispatchers.IO) {
        try {
            val result = service.files().list()
                .setQ("name = '$fileName' and '$folderId' in parents and trashed = false")
                .setFields("files(id, name, modifiedTime)")
                .execute()
            result.files.firstOrNull()
        } catch (e: Exception) {
            Log.e("GDriveManager", "findFileInFolder: FAILED for '$fileName' in $folderId", e)
            null
        }
    }

    // CONTRACT: self-update — generic binary download to an arbitrary destination, unlike
    // downloadMetadataDb which is hardcoded to fetch "metadata.db" from the library folder.
    suspend fun downloadFileToDisk(accountName: String, fileId: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            FileOutputStream(destination).use { output ->
                service.files().get(fileId).executeMediaAndDownloadTo(output)
            }
            true
        } catch (e: Exception) {
            Log.e("GDriveManager", "downloadFileToDisk: FAILED for fileId=$fileId", e)
            false
        }
    }

    suspend fun deleteFile(accountName: String, fileId: String) = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            service.files().delete(fileId).execute()
            Log.d("GDriveManager", "deleteFile: deleted $fileId")
        } catch (e: Exception) {
            // A 404 here just means the file is already gone (deleted by an earlier attempt —
            // e.g. a duplicate response the daemon uploaded twice under the same name for the
            // same putio_id, same root cause as the request-side duplicate-upload bug). The
            // goal state (file gone) is already satisfied, so this isn't a real failure — don't
            // let it drown out genuine errors (permissions, quota, network) in the logs.
            val isAlreadyGone = (e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode == 404
            if (isAlreadyGone) {
                Log.d("GDriveManager", "deleteFile: $fileId already gone (404) — treating as success")
            } else {
                // Silent failure here means a response is never acknowledged, so it's re-fetched
                // and re-processed on every poll forever — this is the #1 suspect for responses
                // piling up in Drive while Putz appears to do nothing with them.
                Log.e("GDriveManager", "deleteFile: FAILED to delete $fileId", e)
            }
        }
    }
}
