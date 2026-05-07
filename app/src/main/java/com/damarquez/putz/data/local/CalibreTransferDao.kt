package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibreTransferDao {
    @Query("SELECT * FROM calibre_transfers ORDER BY addedAt DESC")
    fun getAllTransfers(): Flow<List<CalibreTransferEntity>>

    @Query("SELECT * FROM calibre_transfers WHERE putioFileId = :fileId")
    suspend fun getTransferById(fileId: Long): CalibreTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: CalibreTransferEntity)

    @Update
    suspend fun updateTransfer(transfer: CalibreTransferEntity)

    @Query("DELETE FROM calibre_transfers WHERE putioFileId = :fileId")
    suspend fun deleteTransfer(fileId: Long)
}
