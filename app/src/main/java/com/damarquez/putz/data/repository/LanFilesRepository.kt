package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.LanConnectionDao
import com.damarquez.putz.data.local.LanConnectionEntity
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.util.MetadataUtils
import com.damarquez.putz.data.archive.LanArchiveStream
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LanFilesRepository @Inject constructor(
    private val dao: LanConnectionDao,
    private val secureStorage: SecureStorage,
) {
    companion object {
        const val LAN_ROOT_ID = -3L
        const val LAN_CONNECTION_BASE_ID = -10_000L
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L

        fun connectionVirtualId(entityId: Long) = LAN_CONNECTION_BASE_ID - entityId
    }

    private val smbClient: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_1_1)
                .build()
        )
    }

    private val sortOrder = compareByDescending<PutioFile> { it.isFolder }
        .thenBy { it.name.lowercase() }

    fun getConnections(): Flow<List<LanConnectionEntity>> = dao.getAll()

    suspend fun getConnectionsSync(): List<LanConnectionEntity> = dao.getAllSync()

    suspend fun getConnectionById(id: Long): LanConnectionEntity? = dao.getById(id)

    suspend fun addConnection(
        label: String,
        host: String,
        username: String,
        password: String,
        shareName: String,
    ): String? {
        if (label.isBlank()) return "Label is required"
        if (host.isBlank()) return "Host is required"
        if (shareName.isBlank()) return "Share name is required"
        if (dao.countByLabelExcluding(label.trim(), 0L) > 0) return "A connection named \"$label\" already exists"
        val id = dao.insert(
            LanConnectionEntity(
                label = label.trim(),
                host = host.trim(),
                username = username.trim(),
                shareName = shareName.trim(),
            )
        )
        secureStorage.saveLanPassword(id, password)
        return null
    }

    suspend fun updateConnection(
        id: Long,
        label: String,
        host: String,
        username: String,
        password: String,
        shareName: String,
    ): String? {
        val existing = dao.getById(id) ?: return "Connection not found"
        if (label.isBlank()) return "Label is required"
        if (host.isBlank()) return "Host is required"
        if (shareName.isBlank()) return "Share name is required"
        if (dao.countByLabelExcluding(label.trim(), id) > 0) return "A connection named \"$label\" already exists"
        dao.update(existing.copy(label = label.trim(), host = host.trim(), username = username.trim(), shareName = shareName.trim()))
        secureStorage.saveLanPassword(id, password)
        return null
    }

    suspend fun deleteConnection(id: Long) {
        dao.deleteById(id)
        secureStorage.clearLanPassword(id)
    }

    suspend fun testConnection(
        host: String,
        username: String,
        password: String,
        shareName: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            smbClient.connect(host).use { connection ->
                val session = connection.authenticate(
                    AuthenticationContext(username, password.toCharArray(), null)
                )
                try {
                    val share = session.connectShare(shareName) as DiskShare
                    share.use { share.list("") }
                    null
                } finally {
                    session.close()
                }
            }
        } catch (e: Exception) {
            e.message ?: "Connection failed"
        }
    }

    fun listDirectory(connectionId: Long, lanPath: String, includeAllFiles: Boolean = false): Flow<List<PutioFile>> = flow {
        val conn = dao.getById(connectionId) ?: run {
            emit(emptyList())
            return@flow
        }
        val password = secureStorage.getLanPassword(connectionId)
        val dirPath = lanPath.replace("/", "\\")

        try {
            smbClient.connect(conn.host).use { smbConnection ->
                val session = smbConnection.authenticate(
                    AuthenticationContext(conn.username, password.toCharArray(), null)
                )
                try {
                    val share = session.connectShare(conn.shareName) as DiskShare
                    try {
                        val entries = share.list(dirPath)
                        val results = mutableListOf<PutioFile>()
                        var count = 0

                        for (entry in entries) {
                            val fileName = entry.fileName
                            if (fileName == "." || fileName == "..") continue

                            val isDir = (entry.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L
                            val entryPath = if (lanPath.isEmpty()) fileName else "$lanPath/$fileName"

                            if (isDir || MetadataUtils.isEbook(fileName) || includeAllFiles) {
                                results.add(
                                    PutioFile(
                                        id = lanFileId(connectionId, entryPath),
                                        name = fileName,
                                        fileType = if (isDir) "FOLDER" else "OTHER",
                                        size = entry.endOfFile,
                                        isLan = true,
                                        lanPath = entryPath,
                                        lanConnectionId = connectionId,
                                    )
                                )
                                count++
                                if (count % 50 == 0) emit(results.toList().sortedWith(sortOrder))
                            }
                        }
                        emit(results.sortedWith(sortOrder))
                    } finally {
                        try { share.close() } catch (_: Exception) {}
                    }
                } finally {
                    try { session.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LanFilesRepository", "Error listing $lanPath on conn $connectionId: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun openFileStream(connectionId: Long, lanPath: String): InputStream = withContext(Dispatchers.IO) {
        val conn = dao.getById(connectionId)
            ?: throw IOException("LAN connection not found: $connectionId")
        val password = secureStorage.getLanPassword(connectionId)
        val filePath = lanPath.replace("/", "\\")

        val smbConnection = smbClient.connect(conn.host)
        val session = smbConnection.authenticate(
            AuthenticationContext(conn.username, password.toCharArray(), null)
        )
        val share = session.connectShare(conn.shareName) as DiskShare
        val smbFile = share.openFile(
            filePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val inner = smbFile.inputStream
        object : InputStream() {
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
            override fun available() = inner.available()
            override fun close() {
                try { inner.close() } catch (_: Exception) {}
                try { smbFile.close() } catch (_: Exception) {}
                try { share.close() } catch (_: Exception) {}
                try { session.close() } catch (_: Exception) {}
                try { smbConnection.close() } catch (_: Exception) {}
            }
        }
    }

    suspend fun openArchiveStream(connectionId: Long, lanPath: String): LanArchiveStream = withContext(Dispatchers.IO) {
        val conn = dao.getById(connectionId) ?: throw IOException("LAN connection not found: $connectionId")
        val password = secureStorage.getLanPassword(connectionId)
        val filePath = lanPath.replace("/", "\\")

        val smbConnection = smbClient.connect(conn.host)
        val session = smbConnection.authenticate(
            AuthenticationContext(conn.username, password.toCharArray(), null)
        )
        val share = session.connectShare(conn.shareName) as DiskShare
        val smbFile = share.openFile(
            filePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val fileSize = smbFile.getFileInformation(FileStandardInformation::class.java).endOfFile
        LanArchiveStream(smbFile, share, session, smbConnection, fileSize)
    }

    // Suspend: resolves the connection record and password from the DB/secure store.
    // Call this before entering any blocking section, then pass the result to openWriteStreamBlocking.
    suspend fun prepareWriteContext(connectionId: Long): ((String) -> OutputStream)? {
        val conn = dao.getById(connectionId) ?: return null
        val password = secureStorage.getLanPassword(connectionId)
        return { lanPath -> openWriteStreamBlocking(conn, password, lanPath) }
    }

    // Blocking (non-suspend): all SMBJ calls are synchronous, safe to call from IO threads
    // without wrapping in withContext or runBlocking.
    fun openWriteStreamBlocking(conn: LanConnectionEntity, password: String, lanPath: String): OutputStream {
        val filePath = lanPath.replace("/", "\\")
        val smbConnection = smbClient.connect(conn.host)
        val session = smbConnection.authenticate(
            AuthenticationContext(conn.username, password.toCharArray(), null)
        )
        val share = session.connectShare(conn.shareName) as DiskShare

        val parentPath = filePath.substringBeforeLast("\\", "")
        if (parentPath.isNotEmpty()) {
            createDirectories(share, parentPath)
        }

        val smbFile = share.openFile(
            filePath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        val inner = smbFile.outputStream
        return object : OutputStream() {
            override fun write(b: Int) = inner.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = inner.write(b, off, len)
            override fun flush() = inner.flush()
            override fun close() {
                runCatching { inner.close() }
                runCatching { smbFile.close() }
                runCatching { share.close() }
                runCatching { session.close() }
                runCatching { smbConnection.close() }
            }
        }
    }

    private fun createDirectories(share: DiskShare, path: String) {
        val parts = path.split("\\")
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current\\$part"
            runCatching { share.mkdir(current) }
        }
    }

    private fun lanFileId(connectionId: Long, path: String): Long {
        val raw = connectionId * 1_000_003L + path.hashCode().toLong()
        return -(100_000L + abs(raw) % 900_000_000L)
    }
}
