package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.AppTransferDao
import com.damarquez.putz.data.local.AppTransferEntity
import com.damarquez.putz.data.local.PendingTransferDao
import com.damarquez.putz.data.local.PendingTransferEntity
import com.damarquez.putz.data.model.AddTransferOutcome
import com.damarquez.putz.data.model.MergedTransfer
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.model.TransferStatus
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.util.MagnetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransfersRepository @Inject constructor(
    private val apiClient: PutioApiClient,
    private val dao: AppTransferDao,
    private val pendingDao: PendingTransferDao,
) {

    suspend fun syncAndGetTransfers(token: String): NetworkResult<List<MergedTransfer>> =
        withContext(Dispatchers.IO) {
            when (val result = apiClient.listTransfers(token)) {
                is NetworkResult.Success -> {
                    val apiTransfers = result.data
                    val mergedFromApi = mergeWithLocal(apiTransfers)
                    val activeIds = apiTransfers.map { it.id }
                    
                    if (activeIds.isNotEmpty()) dao.deleteStale(activeIds) else dao.deleteStale(emptyList())

                    val stoppedEntities = dao.getAll().filter { it.isStopped && it.putioId !in activeIds }
                    val stoppedMerged = stoppedEntities.map { entity ->
                        MergedTransfer(
                            transfer = PutioTransfer(
                                id = entity.putioId,
                                name = entity.putioName,
                                status = if (entity.percentDone >= 100) "COMPLETED" else "STOPPED",
                                percentDone = entity.percentDone,
                                size = entity.size,
                            ),
                            appDisplayName = entity.displayName,
                            magnetLink = entity.magnetLink,
                            addedByApp = entity.addedByApp,
                            isStopped = true,
                        )
                    }

                    NetworkResult.Success(mergedFromApi + stoppedMerged)
                }
                is NetworkResult.Error -> result
                NetworkResult.Loading -> NetworkResult.Loading
            }
        }

    suspend fun stopTransfer(token: String, id: Long): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        println("TransfersRepository: Stopping $id")
        val result = apiClient.cancelTransfers(token, listOf(id))
        println("TransfersRepository: Cancel API result: $result")
        if (result is NetworkResult.Success) {
            val local = dao.getById(id)
            if (local != null) {
                println("TransfersRepository: Marking $id as stopped in DAO")
                dao.upsert(local.copy(isStopped = true))
            }
        }
        result
    }

    suspend fun removeTransfer(token: String, id: Long): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        println("TransfersRepository: Removing $id")
        // Try to cancel on put.io in case it's still there
        val result = apiClient.cancelTransfers(token, listOf(id))
        println("TransfersRepository: Cancel API result for remove: $result")
        dao.deleteById(id)
        println("TransfersRepository: Deleted $id from DAO")
        NetworkResult.Success(Unit)
    }

    suspend fun resumeTransfer(token: String, id: Long): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        println("TransfersRepository: Resuming $id")
        val local = dao.getById(id) ?: return@withContext NetworkResult.Error("Transfer not found")
        // Most transfers do have a stored magnet link, but some were added outside this flow
        // (e.g. a .torrent upload, or already on the account before Putz first saw them) and
        // only ever had `source` populated with a non-magnet value. Their info hash is still
        // reliably populated though (from put.io's `hash` field), so reconstruct a magnet URI
        // from it rather than refusing to resume at all.
        val magnet = local.magnetLink
            ?: local.infoHash?.let { "magnet:?xt=urn:btih:$it" }
            ?: return@withContext NetworkResult.Error("Can't resume \"${local.displayName}\" — no magnet link or info hash was ever stored for it")

        // Delete the stopped record first so its infoHash doesn't trigger the duplicate guard in addTransfer.
        dao.deleteById(id)

        when (val outcome = addTransfer(token, magnet)) {
            is AddTransferOutcome.Added -> {
                println("TransfersRepository: Resume add success")
                NetworkResult.Success(Unit)
            }
            is AddTransferOutcome.Queued -> {
                println("TransfersRepository: Resume hit the transfer limit, queued locally")
                NetworkResult.Success(Unit)
            }
            is AddTransferOutcome.Failed -> {
                println("TransfersRepository: Resume add failed: ${outcome.message}")
                dao.upsert(local)
                NetworkResult.Error(outcome.message)
            }
        }
    }

    suspend fun getOrCreateHiddenFolderId(token: String): Long = withContext(Dispatchers.IO) {
        val (files, _) = when (val r = apiClient.listFiles(token, 0L)) {
            is NetworkResult.Success -> r.data
            else -> return@withContext 0L
        }
        files.firstOrNull { it.name == HIDDEN_FOLDER_NAME && it.isFolder }?.id
            ?: when (val r = apiClient.createFolder(token, 0L, HIDDEN_FOLDER_NAME)) {
                is NetworkResult.Success -> r.data.id
                else -> 0L
            }
    }

    suspend fun addTransfer(
        token: String,
        magnetOrUrl: String,
        saveParentId: Long = 0L,
    ): AddTransferOutcome = withContext(Dispatchers.IO) {
        val isMagnet = MagnetParser.isMagnetLink(magnetOrUrl)
        val infoHash = if (isMagnet) MagnetParser.extractInfoHash(magnetOrUrl) else null

        if (infoHash != null) {
            if (dao.getByInfoHash(infoHash) != null || pendingDao.getByInfoHash(infoHash) != null) {
                return@withContext AddTransferOutcome.Failed("This transfer is already in the queue")
            }
        }

        when (val result = apiClient.addTransfer(token, magnetOrUrl, saveParentId)) {
            is NetworkResult.Success -> {
                val transfer = result.data
                val displayName = if (isMagnet) {
                    MagnetParser.extractDisplayName(magnetOrUrl)?.takeIf { it.isNotBlank() }
                        ?: transfer.name
                } else transfer.name

                dao.upsert(buildEntity(transfer, magnetOrUrl, isMagnet, infoHash, displayName))

                AddTransferOutcome.Added(
                    MergedTransfer(
                        transfer = transfer,
                        appDisplayName = displayName,
                        magnetLink = if (isMagnet) magnetOrUrl else null,
                        addedByApp = true,
                    )
                )
            }
            is NetworkResult.Error -> {
                if (result.errorType == TRANSFER_ADD_LIMIT_ERROR_TYPE) {
                    pendingDao.insert(
                        PendingTransferEntity(
                            magnetOrUrl = magnetOrUrl,
                            saveParentId = saveParentId,
                            infoHash = infoHash,
                            displayNameHint = if (isMagnet) MagnetParser.extractDisplayName(magnetOrUrl) else null,
                            addedAt = System.currentTimeMillis(),
                        )
                    )
                    AddTransferOutcome.Queued
                } else {
                    AddTransferOutcome.Failed(result.message)
                }
            }
            NetworkResult.Loading -> AddTransferOutcome.Failed("Loading")
        }
    }

    suspend fun getPendingTransfers(): List<PendingTransferEntity> = withContext(Dispatchers.IO) {
        pendingDao.getAll()
    }

    suspend fun removePendingTransfer(id: Long) = withContext(Dispatchers.IO) {
        pendingDao.deleteById(id)
    }

    /** Retries locally-queued transfers now that put.io may have freed up a slot.
     *  Stops at the first one that still hits the limit, since later ones would too. */
    suspend fun processPendingQueue(token: String): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (pending in pendingDao.getAll()) {
            when (val result = apiClient.addTransfer(token, pending.magnetOrUrl, pending.saveParentId)) {
                is NetworkResult.Success -> {
                    val transfer = result.data
                    val isMagnet = MagnetParser.isMagnetLink(pending.magnetOrUrl)
                    val displayName = pending.displayNameHint?.takeIf { it.isNotBlank() } ?: transfer.name
                    dao.upsert(buildEntity(transfer, pending.magnetOrUrl, isMagnet, pending.infoHash, displayName))
                    pendingDao.deleteById(pending.id)
                    added++
                }
                is NetworkResult.Error -> {
                    if (result.errorType == TRANSFER_ADD_LIMIT_ERROR_TYPE) {
                        break
                    } else {
                        // Permanent failure (bad magnet, revoked token, etc.) — drop it rather than retry forever.
                        pendingDao.deleteById(pending.id)
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
        added
    }

    private fun buildEntity(
        transfer: PutioTransfer,
        magnetOrUrl: String,
        isMagnet: Boolean,
        infoHash: String?,
        displayName: String,
    ) = AppTransferEntity(
        putioId = transfer.id,
        displayName = displayName,
        putioName = transfer.name,
        magnetLink = if (isMagnet) magnetOrUrl else null,
        infoHash = infoHash,
        addedByApp = true,
        addedAt = System.currentTimeMillis(),
        nameResolved = false,
        percentDone = transfer.percentDone,
        size = transfer.size,
    )

    suspend fun updateDisplayName(id: Long, newName: String) = withContext(Dispatchers.IO) {
        val local = dao.getById(id)
        if (local != null) {
            dao.upsert(local.copy(displayName = newName))
        }
    }

    /** Permanently stores history-resolved names for all matching transfers.
     *  History names always win — they come from the actual .torrent file.
     *  Skips if the resolved name is itself the hash (not a real name). */
    suspend fun applyHistoryNames(resolvedByHash: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            var anyUpdated = false
            for (entity in dao.getAll()) {
                val hash = entity.infoHash?.lowercase() ?: continue
                val resolved = resolvedByHash[hash]?.takeIf { it.isNotBlank() } ?: continue
                if (resolved.lowercase() == hash) continue   // resolved name is just the hash itself
                if (entity.displayName == resolved) continue
                dao.upsert(entity.copy(displayName = resolved, nameResolved = true))
                anyUpdated = true
            }
            anyUpdated
        }

    /** Immediately persists a history-resolved name for a specific transfer by put.io ID. */
    suspend fun persistNameById(putioId: Long, name: String) = withContext(Dispatchers.IO) {
        val entity = dao.getById(putioId) ?: return@withContext
        if (entity.displayName == name) return@withContext
        dao.upsert(entity.copy(displayName = name, nameResolved = true))
    }

    private suspend fun mergeWithLocal(apiTransfers: List<PutioTransfer>): List<MergedTransfer> {
        return apiTransfers.map { transfer ->
            val local = dao.getById(transfer.id)
            if (local == null) {
                val magnetLink = transfer.source?.takeIf { it.startsWith("magnet:") }
                val displayName = if (magnetLink != null) {
                    MagnetParser.extractDisplayName(magnetLink)?.takeIf { it.isNotBlank() } ?: transfer.name
                } else {
                    transfer.name
                }

                val entity = AppTransferEntity(
                    putioId = transfer.id,
                    displayName = displayName,
                    putioName = transfer.name,
                    magnetLink = magnetLink,
                    infoHash = transfer.hash?.lowercase(),
                    addedByApp = false,
                    addedAt = System.currentTimeMillis(),
                    nameResolved = isRealName(transfer.name),
                    percentDone = transfer.percentDone,
                    size = transfer.size,
                )
                dao.upsert(entity)
                MergedTransfer(
                    transfer = transfer,
                    appDisplayName = displayName,
                    magnetLink = entity.magnetLink,
                    addedByApp = false,
                )
            } else {
                val shouldResolve = !local.nameResolved &&
                    isRealName(transfer.name) &&
                    TransferStatus.from(transfer.status) in RESOLVING_STATUSES

                val updatedDisplayName = if (shouldResolve) transfer.name else local.displayName
                val updatedMagnetLink = local.magnetLink ?: transfer.source?.takeIf { it.startsWith("magnet:") }
                val updatedInfoHash = local.infoHash ?: transfer.hash?.lowercase()

                if (shouldResolve || local.putioName != transfer.name || local.magnetLink != updatedMagnetLink ||
                    local.infoHash != updatedInfoHash ||
                    local.percentDone != transfer.percentDone || local.size != transfer.size || local.isStopped) {
                    dao.upsert(
                        local.copy(
                            displayName = updatedDisplayName,
                            putioName = transfer.name,
                            magnetLink = updatedMagnetLink,
                            infoHash = updatedInfoHash,
                            nameResolved = local.nameResolved || shouldResolve,
                            percentDone = transfer.percentDone,
                            size = transfer.size,
                            isStopped = false,
                        )
                    )
                }
                MergedTransfer(
                    transfer = transfer,
                    appDisplayName = updatedDisplayName,
                    magnetLink = updatedMagnetLink,
                    addedByApp = local.addedByApp,
                )
            }
        }
    }

    private fun isRealName(name: String): Boolean {
        if (name.isBlank() || name.length <= 3) return false
        if (name.lowercase() == "unknown") return false
        if (name.matches(Regex("[0-9a-fA-F]+"))) return false
        return true
    }

    companion object {
        const val HIDDEN_FOLDER_NAME = ".putz_hidden"
        private const val TRANSFER_ADD_LIMIT_ERROR_TYPE = "TRANSFER_ADD_LIMIT_REACHED"

        private val RESOLVING_STATUSES = setOf(
            TransferStatus.DOWNLOADING,
            TransferStatus.SEEDING,
            TransferStatus.COMPLETING,
            TransferStatus.COMPLETED,
        )
    }
}
