package com.damarquez.putz.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.damarquez.putz.data.local.LocalAttachmentDao
import com.damarquez.putz.data.local.LocalAttachmentEntity
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.util.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFilesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LocalAttachmentDao
) {
    companion object {
        const val LOCAL_ROOT_ID = -2L
        const val LOCAL_FOLDER_PREFIX_ID = -1000L
    }

    suspend fun getAttachments(): List<PutioFile> = withContext(Dispatchers.IO) {
        dao.getAll().map { entity ->
            PutioFile(
                id = LOCAL_FOLDER_PREFIX_ID - entity.id,
                name = entity.name,
                fileType = if (entity.isFolder) "FOLDER" else "OTHER",
                isLocal = true,
                localUri = entity.uri
            )
        }
    }

    suspend fun listLocalFolder(uriString: String): List<PutioFile> = withContext(Dispatchers.IO) {
        val rootUri = Uri.parse(uriString)
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext emptyList()
        
        rootDoc.listFiles()
            .filter { it.isDirectory || MetadataUtils.isEbook(it.name ?: "") }
            .map { doc ->
                PutioFile(
                    id = (doc.uri.toString().hashCode().toLong().let { if (it > 0) -it else it }) 
                        .coerceAtMost(LOCAL_FOLDER_PREFIX_ID - 1000), // Ensure it doesn't clash with attachments
                    name = doc.name ?: "Unknown",
                    fileType = if (doc.isDirectory) "FOLDER" else "OTHER",
                    size = doc.length(),
                    isLocal = true,
                    localUri = doc.uri.toString()
                )
            }
            .sortedWith(compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    suspend fun attach(uri: Uri, name: String, isFolder: Boolean) {
        dao.insert(LocalAttachmentEntity(uri = uri.toString(), name = name, parentId = 0, isFolder = isFolder))
    }

    suspend fun detach(attachmentId: Long) {
        // attachmentId here is the negative ID used in UI
        val realId = LOCAL_FOLDER_PREFIX_ID - attachmentId
        dao.deleteById(realId)
    }
}
