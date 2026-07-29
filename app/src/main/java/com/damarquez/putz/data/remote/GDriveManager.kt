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
    private val scopes = listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_METADATA_READONLY, DriveScopes.DRIVE_READONLY)

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

    // Drive folder browser (read-only) — resolves the Calibre library root without requiring
    // callers to hold a Drive service handle of their own.
    suspend fun resolveLibraryRootId(accountName: String): String? = withContext(Dispatchers.IO) {
        val service = getDriveService(accountName)
        getLibraryFolderId(service)
    }

    // Drive folder browser (read-only) — generic paginated child listing for an arbitrary folder,
    // unlike every other list() call in this file which is hardcoded to a specific filename/folder
    // convention.
    suspend fun listChildren(accountName: String, folderId: String): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val all = mutableListOf<com.google.api.services.drive.model.File>()
            var pageToken: String? = null
            do {
                val result = service.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime)")
                    .setOrderBy("folder,name")
                    .setPageSize(1000)
                    .also { if (pageToken != null) it.pageToken = pageToken }
                    .execute()
                all += result.files ?: emptyList()
                pageToken = result.nextPageToken
            } while (pageToken != null)
            all
        } catch (e: Exception) {
            Log.e("GDriveManager", "listChildren: FAILED for folderId=$folderId", e)
            emptyList()
        }
    }

    // CONTRACT: IPC transport
    // CONTRACT: priority requests lane — isPriority routes into requests/priority/ instead of
    // requests/ itself; see CONTRACTS.md §17. The daemon drains that subfolder completely before
    // ever looking at requests/, so anything placed there is dispatched ahead of everything else.
    suspend fun uploadRequest(accountName: String, fileName: String, content: String, isPriority: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("GDriveManager", "Uploading request $fileName for $accountName (priority=$isPriority)")
            val service = getDriveService(accountName)

            val libFolderId = getLibraryFolderId(service) ?: run {
                Log.e("GDriveManager", "Could not find Calibre library root (metadata.db missing)")
                return@withContext null
            }

            val rootFolderId = findFolder(service, ".calibre_integration", libFolderId)
                ?: createFolder(service, ".calibre_integration", libFolderId)

            val requestsFolderId = findFolder(service, "requests", rootFolderId) ?: createFolder(service, "requests", rootFolderId)
            val destinationFolderId = if (isPriority) {
                findFolder(service, "priority", requestsFolderId) ?: createFolder(service, "priority", requestsFolderId)
            } else {
                requestsFolderId
            }

            // Drive allows multiple files with the same name in one folder — files().create()
            // never replaces an existing one. Without this, every retry for the same book piles
            // up another "req_<id>.json" alongside earlier (possibly broken/stale) copies, and
            // the daemon works through each one independently over several minutes, so an old
            // failure can keep resurfacing and overwriting a newer, already-fixed request's
            // result. Trash any existing copies for this exact filename before uploading the
            // new one so at most one live request per book/action survives at a time. Checks both
            // requests/ and requests/priority/ — a retry or a promote could otherwise leave a
            // stale copy behind in whichever folder it isn't landing in this time.
            try {
                val existing = service.files().list()
                    .setQ("name = '$fileName' and trashed = false and ('$requestsFolderId' in parents or '$destinationFolderId' in parents)")
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
                    parents = listOf(destinationFolderId)
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

    // CONTRACT: mirrors the folders uploadRequest() writes into (requests/ and
    // requests/priority/). Deliberately independent of daemon heartbeat/status — this is
    // Putz's own inspection of what's actually sitting on Drive, not what the daemon reports
    // about itself. Priority requests are always summed into the total, not shown separately.
    suspend fun countPendingRequests(accountName: String): Int? = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: return@withContext null
            val rootFolderId = findFolder(service, ".calibre_integration", libFolderId) ?: return@withContext 0
            val requestsFolderId = findFolder(service, "requests", rootFolderId) ?: return@withContext 0
            val priorityFolderId = findFolder(service, "priority", requestsFolderId)

            countFilesInFolder(service, requestsFolderId) +
                (priorityFolderId?.let { countFilesInFolder(service, it) } ?: 0)
        } catch (e: Exception) {
            Log.e("GDriveManager", "countPendingRequests: FAILED", e)
            null
        }
    }

    // Excludes folders (e.g. the "priority" subfolder itself, a child of requestsFolderId)
    // so a nested folder entry isn't miscounted as a request file.
    private suspend fun countFilesInFolder(service: Drive, folderId: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        var pageToken: String? = null
        do {
            val result = service.files().list()
                .setQ("'$folderId' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'")
                .setFields("nextPageToken, files(id)")
                .setPageSize(1000)
                .also { if (pageToken != null) it.pageToken = pageToken }
                .execute()
            count += result.files?.size ?: 0
            pageToken = result.nextPageToken
        } while (pageToken != null)
        count
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

    // CONTRACT: protection split — fetches metadata_full.db (the unsplit copy sidekick
    // publishes alongside the redacted metadata.db), not metadata.db itself. Putz manages
    // protected books directly via other actions, so the redaction meant for
    // calibreAnywhere/Web's casual viewers only broke Putz's own local verification. See
    // sync_engine.py's _upload_shadow_as_metadata_db(shadow_db, ..., "metadata_full.db").
    suspend fun downloadMetadataDb(accountName: String, destination: File): com.damarquez.putz.data.model.NetworkResult<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d("GDriveManager", "Downloading metadata_full.db for $accountName")
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: return@withContext com.damarquez.putz.data.model.NetworkResult.Error("Could not find Calibre library root (metadata.db missing in your Google Drive)")

            val result = service.files().list()
                .setQ("name = 'metadata_full.db' and '$libFolderId' in parents and trashed = false")
                .setFields("files(id, name)")
                .execute()

            val fileId = result.files.firstOrNull()?.id ?: return@withContext com.damarquez.putz.data.model.NetworkResult.Error("metadata_full.db not found in library folder")
            
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
    // [onListProgress], if given, reports the running file count as each page comes in — a large
    // backlog (thousands of stale response files) can take multiple page round-trips here before
    // this function returns anything, which otherwise looks identical to a frozen UI.
    suspend fun listResponses(
        accountName: String,
        appId: String,
        onListProgress: ((current: Int) -> Unit)? = null,
    ): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
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
                onListProgress?.invoke(all.size)
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

    // CONTRACT: priority requests lane — promotes an already-submitted, not-yet-claimed request
    // in place by reparenting it from requests/ to requests/priority/ (same file ID, so the
    // locally-cached gdriveRequestId stays valid). If the daemon already claimed the request
    // before this lands, the move has no effect — the daemon works off the file ID it claimed,
    // not folder membership — so this is safe to call without checking daemon state first.
    suspend fun promoteRequestToPriority(accountName: String, fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: return@withContext false
            val rootFolderId = findFolder(service, ".calibre_integration", libFolderId) ?: return@withContext false
            val requestsFolderId = findFolder(service, "requests", rootFolderId) ?: return@withContext false
            val priorityFolderId = findFolder(service, "priority", requestsFolderId)
                ?: createFolder(service, "priority", requestsFolderId)

            service.files().update(fileId, null)
                .setAddParents(priorityFolderId)
                .setRemoveParents(requestsFolderId)
                .setFields("id, parents")
                .execute()
            Log.d("GDriveManager", "promoteRequestToPriority: moved $fileId into priority")
            true
        } catch (e: Exception) {
            val isAlreadyGone = (e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode == 404
            if (isAlreadyGone) {
                // Already claimed and deleted/trashed by the daemon — nothing to promote.
                Log.d("GDriveManager", "promoteRequestToPriority: $fileId already gone (404), treating as no-op")
            } else {
                Log.e("GDriveManager", "promoteRequestToPriority: FAILED for $fileId", e)
            }
            false
        }
    }

    suspend fun deleteFile(accountName: String, fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            service.files().delete(fileId).execute()
            Log.d("GDriveManager", "deleteFile: deleted $fileId")
            true
        } catch (e: Exception) {
            // A 404 here just means the file is already gone (deleted by an earlier attempt —
            // e.g. a duplicate response the daemon uploaded twice under the same name for the
            // same putio_id, same root cause as the request-side duplicate-upload bug). The
            // goal state (file gone) is already satisfied, so this isn't a real failure — don't
            // let it drown out genuine errors (permissions, quota, network) in the logs.
            val isAlreadyGone = (e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode == 404
            if (isAlreadyGone) {
                Log.d("GDriveManager", "deleteFile: $fileId already gone (404) — treating as success")
                true
            } else {
                // The caller persists this fileId as a pending deletion (see
                // PendingResponseDeletionDao) so a genuine failure here doesn't force a full
                // redownload/reprocess of the file on every subsequent poll — just a retried
                // delete call.
                Log.e("GDriveManager", "deleteFile: FAILED to delete $fileId", e)
                false
            }
        }
    }
}
