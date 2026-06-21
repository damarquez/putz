package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingTransferDao {

    @Query("SELECT * FROM pending_transfers ORDER BY addedAt ASC")
    suspend fun getAll(): List<PendingTransferEntity>

    @Query("SELECT * FROM pending_transfers WHERE infoHash = :hash LIMIT 1")
    suspend fun getByInfoHash(hash: String): PendingTransferEntity?

    @Insert
    suspend fun insert(entity: PendingTransferEntity): Long

    @Query("DELETE FROM pending_transfers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
