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

    internal fun getDriveService(accountName: String): Drive {
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

            val tempFile = File(context.cacheDir, fileName).apply { writeText(content) }
            val metadata = com.google.api.services.drive.model.File().apply {
                name = fileName
                parents = listOf(requestsFolderId)
            }
            val mediaContent = FileContent("application/json", tempFile)
            val uploaded = service.files().create(metadata, mediaContent).setFields("id").execute()
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

    suspend fun listResponses(accountName: String): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            val libFolderId = getLibraryFolderId(service) ?: return@withContext emptyList()

            val rootId = findFolder(service, ".calibre_integration", libFolderId) ?: return@withContext emptyList()
            val responsesId = findFolder(service, "responses", rootId) ?: return@withContext emptyList()

            val result = service.files().list()
                .setQ("'$responsesId' in parents and trashed = false")
                .setFields("files(id, name)")
                .execute()
            result.files ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun downloadFileContent(accountName: String, fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            service.files().get(fileId).executeMediaAsInputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
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

    suspend fun deleteFile(accountName: String, fileId: String) = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(accountName)
            service.files().delete(fileId).execute()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
