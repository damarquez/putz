package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.AppTransferDao
import com.damarquez.putz.data.local.AppTransferEntity
import com.damarquez.putz.data.model.AddTransferOutcome
import com.damarquez.putz.data.model.HistoryFileEntry
import com.damarquez.putz.data.model.MergedTransfer
import com.damarquez.putz.data.model.NetworkResult
import com.damarquez.putz.data.model.PutioTransfer
import com.damarquez.putz.data.model.TransferStatus
import com.damarquez.putz.data.remote.PutioApiClient
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.util.MagnetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransfersRepository @Inject constructor(
    private val apiClient: PutioApiClient,
    private val dao: AppTransferDao,
    private val calibreRepository: CalibreRepository,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: TransferHistoryRepository,
) {

    // IDs the user just deleted. Kept until put.io stops reporting them so a slow cancel
    // response doesn't cause the transfer to resurrect on the next poll.
    private val pendingRemovalIds = mutableSetOf<Long>()

    suspend fun syncAndGetTransfers(token: String): NetworkResult<List<MergedTransfer>> =
        withContext(Dispatchers.IO) {
            when (val result = apiClient.listTransfers(token)) {
                is NetworkResult.Success -> {
                    val apiTransfers = result.data
                    val apiIds = apiTransfers.map { it.id }.toSet()
                    pendingRemovalIds.removeAll { it !in apiIds }
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
        pendingRemovalIds.add(id)
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

        when (val outcome = addTransfer(token, magnet, saveParentId = local.saveParentId, displayNameOverride = local.displayName)) {
            is AddTransferOutcome.Added -> {
                println("TransfersRepository: Resume add success")
                NetworkResult.Success(Unit)
            }
            is AddTransferOutcome.Queued -> {
                println("TransfersRepository: Resume hit the transfer limit, flagged in shared history")
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
        displayNameOverride: String? = null,
        bypassHistoryCheck: Boolean = false,
    ): AddTransferOutcome = withContext(Dispatchers.IO) {
        val isMagnet = MagnetParser.isMagnetLink(magnetOrUrl)
        val infoHash = if (isMagnet) MagnetParser.extractInfoHash(magnetOrUrl) else null
        val parsedName = if (isMagnet) MagnetParser.extractDisplayName(magnetOrUrl)?.takeIf { it.isNotBlank() } else null
        val displayName = displayNameOverride?.takeIf { it.isNotBlank() }
            ?: parsedName
            ?: (if (isMagnet) (infoHash ?: magnetOrUrl) else magnetOrUrl)

        if (infoHash != null && dao.getByInfoHash(infoHash) != null) {
            return@withContext AddTransferOutcome.Failed("This transfer is already in the queue")
        }

        // bypassHistoryCheck: the user already saw and explicitly dismissed this exact warning
        // once (via "Add anyway" in AddTransferSheet, itself gated behind removing the entry
        // from history first) — a second silent block here would defeat that explicit choice,
        // and worse, could never be un-stuck: this check reads the locally cached history blob
        // (getCachedHistory()), which can lag the daemon's actual state by however long the
        // re-upload + next heartbeat takes to propagate after a FORGET_TRANSFER_HISTORY delete.
        if (!bypassHistoryCheck && infoHash != null && isCompletedInHistory(infoHash)) {
            return@withContext AddTransferOutcome.Failed(
                "This was already completed previously — adding it again would re-download everything from scratch"
            )
        }

        when (val result = apiClient.addTransfer(token, magnetOrUrl, saveParentId)) {
            is NetworkResult.Success -> {
                val transfer = result.data
                val resolvedName = displayNameOverride?.takeIf { it.isNotBlank() }
                    ?: parsedName
                    ?: transfer.name

                dao.upsert(buildEntity(transfer, magnetOrUrl, isMagnet, infoHash, resolvedName))

                AddTransferOutcome.Added(
                    MergedTransfer(
                        transfer = transfer,
                        appDisplayName = resolvedName,
                        magnetLink = if (isMagnet) magnetOrUrl else null,
                        addedByApp = true,
                    )
                )
            }
            is NetworkResult.Error -> {
                if (result.errorType == TRANSFER_ADD_LIMIT_ERROR_TYPE && infoHash != null) {
                    val entry = registerHistoryStatus(
                        infoHash = infoHash,
                        label = displayName,
                        magnetUri = magnetOrUrl,
                        status = HISTORY_STATUS_QUEUED_OUTSIDE_PUTIO,
                    )
                    if (entry != null) {
                        AddTransferOutcome.Queued(entry)
                    } else {
                        AddTransferOutcome.Failed(
                            "Active transfer limit reached, but couldn't save to shared history — check your connection and try again"
                        )
                    }
                } else {
                    AddTransferOutcome.Failed(result.message)
                }
            }
            NetworkResult.Loading -> AddTransferOutcome.Failed("Loading")
        }
    }

    /** User-triggered retry for a magnet that previously hit put.io's transfer limit and was
     *  flagged [HISTORY_STATUS_QUEUED_OUTSIDE_PUTIO] in the shared history. Never runs automatically —
     *  put.io's own queue manages everything below the limit; above it, the user decides when to retry. */
    suspend fun activateHistoryEntry(token: String, entry: HistoryFileEntry): AddTransferOutcome =
        withContext(Dispatchers.IO) {
            if (entry.status == HISTORY_STATUS_COMPLETED) {
                return@withContext AddTransferOutcome.Failed(
                    "Already completed — activating would re-download this torrent from scratch"
                )
            }

            val magnet = entry.magnetUri
                ?: return@withContext AddTransferOutcome.Failed("No magnet link stored for this entry")

            when (val result = apiClient.addTransfer(token, magnet, 0L)) {
                is NetworkResult.Success -> {
                    val transfer = result.data
                    val isMagnet = MagnetParser.isMagnetLink(magnet)
                    dao.upsert(buildEntity(transfer, magnet, isMagnet, entry.infoHash, entry.label))
                    registerHistoryStatus(
                        infoHash = entry.infoHash,
                        label = entry.label,
                        magnetUri = magnet,
                        status = transfer.status,
                        putioId = transfer.id,
                        putioName = transfer.name,
                    )
                    AddTransferOutcome.Added(
                        MergedTransfer(
                            transfer = transfer,
                            appDisplayName = entry.label,
                            magnetLink = magnet,
                            addedByApp = true,
                        )
                    )
                }
                is NetworkResult.Error -> {
                    if (result.errorType == TRANSFER_ADD_LIMIT_ERROR_TYPE) {
                        val updated = registerHistoryStatus(
                            infoHash = entry.infoHash,
                            label = entry.label,
                            magnetUri = magnet,
                            status = HISTORY_STATUS_QUEUED_OUTSIDE_PUTIO,
                        )
                        if (updated != null) {
                            AddTransferOutcome.Queued(updated)
                        } else {
                            AddTransferOutcome.Failed(
                                "Still at the transfer limit, and couldn't reach shared history to confirm — try again"
                            )
                        }
                    } else {
                        AddTransferOutcome.Failed(result.message)
                    }
                }
                NetworkResult.Loading -> AddTransferOutcome.Failed("Loading")
            }
        }

    /** Cancels the put.io transfer JOB for an already-COMPLETED history entry — this only makes
     *  put.io forget/stop tracking that transfer so it can't resurface as WAITING/re-add itself;
     *  it never touches the downloaded files or the daemon's stubs. Safe to call even if the job
     *  is already gone from put.io (e.g. auto-cleared) — that's just treated as success. */
    suspend fun forgetCompletedTransfer(token: String, entry: HistoryFileEntry): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            if (entry.status != HISTORY_STATUS_COMPLETED) {
                return@withContext NetworkResult.Error("Only completed transfers can be forgotten")
            }
            val putioId = entry.putioId
                ?: return@withContext NetworkResult.Error("No put.io transfer ID stored for this entry")
            apiClient.cancelTransfers(token, listOf(putioId))
        }

    /** Drops a never-added entry out of the "Waiting for a free slot" list. There's no delete
     *  endpoint on the daemon, so this just flips the status away from the queued flag.
     *  Returns null if the flip couldn't be confirmed durable — callers should leave the entry
     *  showing rather than optimistically hiding something that might still be queued. */
    suspend fun cancelQueuedHistoryEntry(entry: HistoryFileEntry): HistoryFileEntry? = withContext(Dispatchers.IO) {
        registerHistoryStatus(
            infoHash = entry.infoHash,
            label = entry.label,
            magnetUri = entry.magnetUri,
            status = HISTORY_STATUS_CANCELLED_OUTSIDE_PUTIO,
        )
    }

    /** Registers a status with the shared history and only returns the entry once the write is
     *  confirmed durable (reached Drive/LAN, so the daemon will see it even if offline right now).
     *  Returns null otherwise — never claim something is tracked when it might not be. */
    private suspend fun registerHistoryStatus(
        infoHash: String,
        label: String,
        magnetUri: String?,
        status: String,
        putioId: Long? = null,
        putioName: String? = null,
    ): HistoryFileEntry? {
        val googleAccount = settingsRepository.googleTokenFlow.first()
        if (googleAccount.isBlank()) return null
        val confirmed = calibreRepository.registerTransferHistory(
            // Real transfers key the daemon's request filename by put.io transfer ID; entries
            // that were never accepted by put.io don't have one, so derive a stable per-hash
            // placeholder instead. The daemon's actual lookup/dedup key is info_hash, not this value.
            putioTransferId = putioId ?: placeholderTransferId(infoHash),
            infoHash = infoHash,
            label = label,
            putioName = putioName,
            magnetUri = magnetUri,
            putioId = putioId,
            status = status,
            googleAccount = googleAccount,
        )
        if (!confirmed) return null
        return HistoryFileEntry(
            infoHash = infoHash,
            label = label,
            status = status,
            addedAt = System.currentTimeMillis() / 1000L,
            magnetUri = magnetUri,
            putioId = putioId,
        )
    }

    private fun placeholderTransferId(infoHash: String): Long =
        (infoHash.hashCode().toLong() and 0x7fffffffL)

    /** Checks the locally cached shared history (no network call) for a COMPLETED entry with
     *  this info hash, so any add-transfer path can refuse to resubmit a torrent whose content
     *  has already been fully downloaded and processed once before. */
    private suspend fun isCompletedInHistory(infoHash: String): Boolean {
        val entries = historyRepository.getCachedHistory()?.entries ?: return false
        return entries.any { it.infoHash.equals(infoHash, ignoreCase = true) && it.status == HISTORY_STATUS_COMPLETED }
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
        saveParentId = transfer.saveParentId,
    )

    /** User-initiated rename ("Edit display name" / properties pencil button). Marked
     *  manuallyRenamed so neither put.io's own resolved name (mergeWithLocal's shouldResolve)
     *  nor the torrent-history resolved name (applyHistoryNames) can silently revert it. */
    suspend fun updateDisplayName(id: Long, newName: String) = withContext(Dispatchers.IO) {
        val local = dao.getById(id)
        if (local != null) {
            dao.upsert(local.copy(displayName = newName, nameResolved = true, manuallyRenamed = true))
        }
    }

    /** Permanently stores history-resolved names for all matching transfers.
     *  History names always win — they come from the actual .torrent file.
     *  Skips if the resolved name is itself the hash (not a real name), or if the user has
     *  manually renamed this transfer (manual renames always win). */
    suspend fun applyHistoryNames(resolvedByHash: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            var anyUpdated = false
            for (entity in dao.getAll()) {
                if (entity.manuallyRenamed) continue
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
        if (entity.manuallyRenamed) return@withContext
        if (entity.displayName == name) return@withContext
        dao.upsert(entity.copy(displayName = name, nameResolved = true))
    }

    private suspend fun mergeWithLocal(apiTransfers: List<PutioTransfer>): List<MergedTransfer> {
        return apiTransfers.filter { it.id !in pendingRemovalIds }.map { transfer ->
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
        const val HISTORY_STATUS_QUEUED_OUTSIDE_PUTIO = "QUEUED_OUTSIDE_PUTIO"
        const val HISTORY_STATUS_CANCELLED_OUTSIDE_PUTIO = "CANCELLED_OUTSIDE_PUTIO"
        const val HISTORY_STATUS_COMPLETED = "COMPLETED"

        private val RESOLVING_STATUSES = setOf(
            TransferStatus.DOWNLOADING,
            TransferStatus.SEEDING,
            TransferStatus.COMPLETING,
            TransferStatus.COMPLETED,
        )
    }
}
