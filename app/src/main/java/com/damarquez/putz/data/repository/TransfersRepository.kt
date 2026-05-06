package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.AppTransferDao
import com.damarquez.putz.data.local.AppTransferEntity
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
) {

    suspend fun syncAndGetTransfers(token: String): NetworkResult<List<MergedTransfer>> =
        withContext(Dispatchers.IO) {
            when (val result = apiClient.listTransfers(token)) {
                is NetworkResult.Success -> {
                    val apiTransfers = result.data
                    val merged = mergeWithLocal(apiTransfers)
                    val activeIds = apiTransfers.map { it.id }
                    if (activeIds.isNotEmpty()) dao.deleteStale(activeIds) else dao.deleteAll()
                    NetworkResult.Success(merged)
                }
                is NetworkResult.Error -> result
                NetworkResult.Loading -> NetworkResult.Loading
            }
        }

    suspend fun addTransfer(
        token: String,
        magnetOrUrl: String,
        saveParentId: Long = 0L,
    ): NetworkResult<MergedTransfer> = withContext(Dispatchers.IO) {
        val isMagnet = MagnetParser.isMagnetLink(magnetOrUrl)
        val infoHash = if (isMagnet) MagnetParser.extractInfoHash(magnetOrUrl) else null

        if (infoHash != null) {
            val existing = dao.getByInfoHash(infoHash)
            if (existing != null) {
                return@withContext NetworkResult.Error("This transfer is already in the queue")
            }
        }

        when (val result = apiClient.addTransfer(token, magnetOrUrl, saveParentId)) {
            is NetworkResult.Success -> {
                val transfer = result.data
                val displayName = if (isMagnet) {
                    MagnetParser.extractDisplayName(magnetOrUrl)?.takeIf { it.isNotBlank() }
                        ?: transfer.name
                } else transfer.name

                val entity = AppTransferEntity(
                    putioId = transfer.id,
                    displayName = displayName,
                    putioName = transfer.name,
                    magnetLink = if (isMagnet) magnetOrUrl else null,
                    infoHash = infoHash,
                    addedByApp = true,
                    addedAt = System.currentTimeMillis(),
                    nameResolved = false,
                )
                dao.upsert(entity)

                NetworkResult.Success(
                    MergedTransfer(
                        transfer = transfer,
                        appDisplayName = displayName,
                        magnetLink = entity.magnetLink,
                        addedByApp = true,
                    )
                )
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    private suspend fun mergeWithLocal(apiTransfers: List<PutioTransfer>): List<MergedTransfer> {
        return apiTransfers.map { transfer ->
            val local = dao.getById(transfer.id)
            if (local == null) {
                val entity = AppTransferEntity(
                    putioId = transfer.id,
                    displayName = transfer.name,
                    putioName = transfer.name,
                    magnetLink = null,
                    infoHash = null,
                    addedByApp = false,
                    addedAt = System.currentTimeMillis(),
                    nameResolved = isRealName(transfer.name),
                )
                dao.upsert(entity)
                MergedTransfer(
                    transfer = transfer,
                    appDisplayName = transfer.name,
                    magnetLink = null,
                    addedByApp = false,
                )
            } else {
                val shouldResolve = !local.nameResolved &&
                    isRealName(transfer.name) &&
                    TransferStatus.from(transfer.status) in RESOLVING_STATUSES

                val updatedDisplayName = if (shouldResolve) transfer.name else local.displayName
                if (shouldResolve || local.putioName != transfer.name) {
                    dao.upsert(
                        local.copy(
                            displayName = updatedDisplayName,
                            putioName = transfer.name,
                            nameResolved = local.nameResolved || shouldResolve,
                        )
                    )
                }
                MergedTransfer(
                    transfer = transfer,
                    appDisplayName = updatedDisplayName,
                    magnetLink = local.magnetLink,
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
        private val RESOLVING_STATUSES = setOf(
            TransferStatus.DOWNLOADING,
            TransferStatus.SEEDING,
            TransferStatus.COMPLETING,
            TransferStatus.COMPLETED,
        )
    }
}
