package com.damarquez.putz.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.damarquez.putz.data.archive.LocalArchiveStream
import com.damarquez.putz.data.archive.PutioArchiveStream
import com.damarquez.putz.data.model.ArchiveDestination
import com.damarquez.putz.data.model.ArchiveEntry
import com.damarquez.putz.data.model.ArchiveSource
import com.damarquez.putz.data.model.ExtractionProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipNativeInitializationException
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.settings.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

@Singleton
class ArchiveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lanFilesRepository: LanFilesRepository,
    private val filesRepository: FilesRepository,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
) {
    private val initialized: Boolean by lazy {
        try {
            SevenZip.initSevenZipFromPlatformJAR()
            true
        } catch (e: SevenZipNativeInitializationException) {
            android.util.Log.e("ArchiveRepository", "7-Zip init failed: ${e.message}")
            false
        }
    }

    suspend fun listEntries(source: ArchiveSource): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        check(initialized) { "7-Zip native library failed to initialize" }
        val stream = openStream(source)
        try {
            val inArchive = SevenZip.openInArchive(null, stream)
            try {
                buildEntryList(inArchive)
            } finally {
                runCatching { inArchive.close() }
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    fun extractEntries(
        source: ArchiveSource,
        selectedEntries: List<ArchiveEntry>,
        destination: ArchiveDestination,
        stripPrefix: String,
    ): Flow<ExtractionProgress> = flow {
        emit(ExtractionProgress.Working)
        // Pre-fetch the LAN write factory while still in suspend context (avoids runBlocking later).
        val lanWriteFactory: ((String) -> OutputStream)? = if (destination is ArchiveDestination.Lan)
            lanFilesRepository.prepareWriteContext(destination.connectionId) else null

        val result = try {
            withContext(Dispatchers.IO) {
                check(initialized) { "7-Zip native library failed to initialize" }
                val stream = openStream(source)
                try {
                    val inArchive = SevenZip.openInArchive(null, stream)
                    try {
                        val allEntries = buildEntryList(inArchive)
                        val pathToIndex = allEntries.mapIndexed { i, e -> e.path to i }.toMap()
                        val expanded = expandWithDescendants(selectedEntries, allEntries)
                        val indices = expanded.mapNotNull { pathToIndex[it.path] }.toIntArray()
                        val prefix = if (stripPrefix.isEmpty()) "" else "$stripPrefix/"

                        var extractedCount = 0
                        var lastError: String? = null

                        val putioToken: String? = if (destination is ArchiveDestination.Putio)
                            runBlocking { settingsRepository.authTokenFlow.first() } else null
                        val folderCache = mutableMapOf<String, Long>()

                        inArchive.extract(indices, false, object : IArchiveExtractCallback {
                            private var currentOs: OutputStream? = null
                            private var currentRelPath: String? = null

                            override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                                val entry = allEntries.getOrNull(index) ?: return null
                                if (entry.isDirectory || extractAskMode != ExtractAskMode.EXTRACT) return null
                                val relative = entry.path.removePrefix(prefix).trimStart('/')
                                if (relative.isEmpty()) return null
                                val os: OutputStream = if (destination is ArchiveDestination.Putio) {
                                    currentRelPath = relative
                                    ByteArrayOutputStream()
                                } else {
                                    runCatching {
                                        openDestinationStream(destination, relative, lanWriteFactory)
                                    }.getOrNull() ?: return null
                                }
                                currentOs = os
                                return ISequentialOutStream { data -> os.write(data); data.size }
                            }

                            override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                            override fun setOperationResult(result: ExtractOperationResult) {
                                val os = currentOs
                                currentOs = null
                                if (destination is ArchiveDestination.Putio && result == ExtractOperationResult.OK) {
                                    val baos = os as? ByteArrayOutputStream
                                    val relPath = currentRelPath
                                    currentRelPath = null
                                    if (baos != null && relPath != null && putioToken != null) {
                                        val bytes = baos.toByteArray()
                                        val uploadResult = runBlocking {
                                            val dirPart = relPath.substringBeforeLast('/', "")
                                            val fileName = relPath.substringAfterLast('/')
                                            val parentId = if (dirPart.isEmpty()) destination.parentFolderId
                                                else getOrCreatePutioFolderPath(putioToken, dirPart, destination.parentFolderId, folderCache)
                                            filesRepository.uploadFileFromStream(
                                                putioToken, parentId, fileName,
                                                ByteArrayInputStream(bytes), bytes.size.toLong()
                                            )
                                        }
                                        if (uploadResult is NetworkResult.Success) extractedCount++
                                        else lastError = "Upload failed for $relPath"
                                    }
                                } else {
                                    runCatching { os?.close() }
                                    currentRelPath = null
                                    if (result == ExtractOperationResult.OK) extractedCount++
                                    else lastError = "One or more files failed to extract (${result.name})"
                                }
                            }

                            override fun setTotal(total: Long) {}
                            override fun setCompleted(complete: Long) {}
                        })

                        if (lastError != null) ExtractionProgress.Error(lastError!!)
                        else ExtractionProgress.Done(extractedCount)
                    } finally {
                        runCatching { inArchive.close() }
                    }
                } finally {
                    runCatching { stream.close() }
                }
            }
        } catch (e: Exception) {
            ExtractionProgress.Error(e.message ?: "Extraction failed")
        }

        emit(result)
    }

    private suspend fun openStream(source: ArchiveSource): IInStream = when (source) {
        is ArchiveSource.Local -> {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
                ?: throw IOException("Cannot open archive: ${source.uri}")
            LocalArchiveStream(pfd)
        }
        is ArchiveSource.Lan -> lanFilesRepository.openArchiveStream(source.connectionId, source.path)
        is ArchiveSource.Putio -> PutioArchiveStream(source.downloadUrl, source.fileSize, okHttpClient)
    }

    private fun buildEntryList(inArchive: IInArchive): List<ArchiveEntry> {
        val count = inArchive.numberOfItems
        return (0 until count).mapNotNull { i ->
            runCatching {
                val path = (inArchive.getProperty(i, PropID.PATH) as? String) ?: return@mapNotNull null
                val normalizedPath = path.replace('\\', '/').trimEnd('/')
                if (normalizedPath.isEmpty()) return@mapNotNull null
                val isDir = (inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean) ?: false
                val size = (inArchive.getProperty(i, PropID.SIZE) as? Long) ?: 0L
                val compressed = (inArchive.getProperty(i, PropID.PACKED_SIZE) as? Long) ?: 0L
                ArchiveEntry(
                    path = normalizedPath,
                    name = normalizedPath.substringAfterLast('/'),
                    isDirectory = isDir,
                    size = size,
                    compressedSize = compressed,
                )
            }.getOrNull()
        }
    }

    private fun expandWithDescendants(
        selected: List<ArchiveEntry>,
        all: List<ArchiveEntry>,
    ): List<ArchiveEntry> {
        val result = LinkedHashSet<ArchiveEntry>()
        for (entry in selected) {
            result.add(entry)
            if (entry.isDirectory) {
                val prefix = "${entry.path}/"
                all.filter { it.path.startsWith(prefix) }.forEach { result.add(it) }
            }
        }
        return result.toList()
    }

    private suspend fun getOrCreatePutioFolderPath(
        token: String,
        dirPath: String,
        rootFolderId: Long,
        cache: MutableMap<String, Long>,
    ): Long {
        var parentId = rootFolderId
        val accumulated = StringBuilder()
        for (part in dirPath.split('/')) {
            if (accumulated.isNotEmpty()) accumulated.append('/')
            accumulated.append(part)
            val key = accumulated.toString()
            parentId = cache.getOrPut(key) {
                val result = filesRepository.createFolder(token, parentId, part)
                (result as? NetworkResult.Success)?.data?.id ?: parentId
            }
        }
        return parentId
    }

    private fun openDestinationStream(
        destination: ArchiveDestination,
        relativePath: String,
        lanWriteFactory: ((String) -> OutputStream)?,
    ): OutputStream = when (destination) {
        is ArchiveDestination.Local -> {
            createLocalFile(Uri.parse(destination.treeUri), relativePath)
                ?: throw IOException("Cannot create local file: $relativePath")
        }
        is ArchiveDestination.Lan -> {
            val lanPath = if (destination.basePath.isEmpty()) relativePath
                else "${destination.basePath}/$relativePath"
            lanWriteFactory?.invoke(lanPath)
                ?: throw IOException("LAN write context unavailable")
        }
        is ArchiveDestination.Putio -> throw IOException("Putio destination must be handled separately")
    }

    private fun createLocalFile(treeUri: Uri, relativePath: String): OutputStream? {
        val parts = relativePath.split('/')
        var parent = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        for (i in 0 until parts.size - 1) {
            parent = parent.findFile(parts[i])?.takeIf { it.isDirectory }
                ?: parent.createDirectory(parts[i]) ?: return null
        }
        val fileName = parts.last()
        val existing = parent.findFile(fileName)
        val fileDoc = if (existing != null && !existing.isDirectory) existing
            else parent.createFile("application/octet-stream", fileName) ?: return null
        return context.contentResolver.openOutputStream(fileDoc.uri)
    }
}
