package com.damarquez.putz.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.damarquez.putz.data.local.HiddenLocalFileDao
import com.damarquez.putz.data.local.HiddenLocalFileEntity
import com.damarquez.putz.data.local.LocalAttachmentDao
import com.damarquez.putz.data.local.LocalAttachmentEntity
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.util.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFilesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LocalAttachmentDao,
    private val hiddenDao: HiddenLocalFileDao,
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

    fun listLocalFolder(uriString: String): Flow<List<PutioFile>> = flow {
        val rootUri = Uri.parse(uriString)
        val documentId = DocumentsContract.getTreeDocumentId(rootUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, documentId)
        
        val hiddenUris = hiddenDao.getAllUris().toSet()
        val results = mutableListOf<PutioFile>()
        var count = 0

        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx)
                val name = cursor.getString(nameIdx) ?: "Unknown"
                val mime = cursor.getString(mimeIdx)
                val size = cursor.getLong(sizeIdx)
                
                val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                if (docUri.toString() in hiddenUris) continue

                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir || MetadataUtils.isEbook(name)) {
                    results.add(
                        PutioFile(
                            id = (docUri.toString().hashCode().toLong().let { if (it > 0) -it else it })
                                .coerceAtMost(LOCAL_FOLDER_PREFIX_ID - 1000),
                            name = name,
                            fileType = if (isDir) "FOLDER" else "OTHER",
                            size = size,
                            isLocal = true,
                            localUri = docUri.toString()
                        )
                    )
                    count++
                    
                    // Emit every 50 items for responsiveness
                    if (count % 50 == 0) {
                        emit(results.toList().sortedWith(compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() }))
                    }
                }
            }
        }
        // Final emission
        emit(results.sortedWith(compareByDescending<PutioFile> { it.isFolder }.thenBy { it.name.lowercase() }))
    }.flowOn(Dispatchers.IO)

    suspend fun attach(uri: Uri, name: String, isFolder: Boolean) {
        dao.insert(LocalAttachmentEntity(uri = uri.toString(), name = name, parentId = 0, isFolder = isFolder))
    }

    suspend fun detach(attachmentId: Long) {
        // attachmentId here is the negative ID used in UI
        val realId = LOCAL_FOLDER_PREFIX_ID - attachmentId
        hiddenDao.deleteByParentId(realId)
        dao.deleteById(realId)
    }

    suspend fun detachOrHide(uri: String) {
        val attachments = dao.getAll()
        val direct = attachments.find { it.uri == uri }
        
        if (direct != null) {
            detach(LOCAL_FOLDER_PREFIX_ID - direct.id)
        } else {
            // Find which attached folder contains this URI
            val parent = attachments.find { it.isFolder && uri.startsWith(it.uri) }
            if (parent != null) {
                hiddenDao.insert(HiddenLocalFileEntity(uri, parent.id))
            }
        }
    }
}
