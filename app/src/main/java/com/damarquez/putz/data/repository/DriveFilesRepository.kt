package com.damarquez.putz.data.repository

import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.remote.GDriveManager
import com.damarquez.putz.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

// Read-only Google Drive folder/file browser, scoped to the Calibre library folder tree. Mirrors
// LanFilesRepository's shape: Drive file ids are opaque strings, but PutioFile.id is a Long, so
// each entry's real identity lives in driveFileId while id is a synthetic hash used only for
// list keying/diffing.
@Singleton
class DriveFilesRepository @Inject constructor(
    private val gDriveManager: GDriveManager,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val DRIVE_ROOT_ID = -5L
    }

    private val sortOrder = compareByDescending<PutioFile> { it.isFolder }
        .thenBy { it.name.lowercase() }

    // Mirrors FilesRepository's own fileCache — keeps a returning-to-a-folder tab switch instant
    // (no re-fetch) instead of hitting the Drive API again every time; the screen's own
    // refresh/sync button (isRefresh = true in FilesViewModel.loadFiles) still bypasses this.
    private val folderCache = mutableMapOf<String, List<PutioFile>>()
    private var cachedLibraryRootId: String? = null

    fun getCached(folderId: String): List<PutioFile>? = folderCache[folderId]

    private fun driveFileHash(id: String): Long {
        return -(1_000_000L + abs(id.hashCode().toLong()) % 900_000_000L)
    }

    suspend fun resolveLibraryRootId(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) cachedLibraryRootId?.let { return it }
        val accountName = settingsRepository.googleTokenFlow.first()
        if (accountName.isBlank()) return null
        return gDriveManager.resolveLibraryRootId(accountName)?.also { cachedLibraryRootId = it }
    }

    fun listDirectory(folderId: String): Flow<List<PutioFile>> = flow {
        val accountName = settingsRepository.googleTokenFlow.first()
        val children = if (accountName.isBlank()) emptyList() else gDriveManager.listChildren(accountName, folderId)
        val files = children.map { child ->
            val isFolder = child.mimeType == "application/vnd.google-apps.folder"
            PutioFile(
                id = driveFileHash(child.id),
                name = child.name,
                fileType = if (isFolder) "FOLDER" else "OTHER",
                size = if (isFolder) 0L else (child.getSize() ?: 0L),
                isDrive = true,
                driveFileId = child.id,
            )
        }.sortedWith(sortOrder)
        folderCache[folderId] = files
        emit(files)
    }

    suspend fun downloadToFile(fileId: String, destination: File): Boolean {
        val accountName = settingsRepository.googleTokenFlow.first()
        if (accountName.isBlank()) return false
        return gDriveManager.downloadFileToDisk(accountName, fileId, destination)
    }
}
