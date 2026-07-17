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

    // Scoped in SQL rather than loading the whole table (see CalibreRepository.findPendingTransfer):
    // this runs once per row in the batch-send-to-Calibre confirmation sheet, so for a large
    // selection reviewed against a long transfer history, materializing every row via getAllTransfers()
    // repeatedly was allocating the entire table's worth of entities (including sizeable text/JSON
    // columns like lastRequestPayload/batchData) per row, which OOM'd the process
    // ("SELECT * FROM calibre_transfers ORDER BY addedAt DESC out of memory" in the crash log).
    // allPutioFileIds is a comma-separated list; the LIKE wraps both sides in commas so a bare
    // numeric match can't false-positive on a substring of a longer id.
    @Query("""
        SELECT * FROM calibre_transfers
        WHERE status NOT IN ('COMPLETED', 'FAILED')
        AND (
            fileName = :fileName
            OR putioFileId = :fileId
            OR (',' || allPutioFileIds || ',') LIKE ('%,' || :fileId || ',%')
        )
        LIMIT 1
    """)
    suspend fun findPendingTransfer(fileId: Long, fileName: String): CalibreTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: CalibreTransferEntity)

    @Update
    suspend fun updateTransfer(transfer: CalibreTransferEntity)

    @Query("DELETE FROM calibre_transfers WHERE putioFileId = :fileId")
    suspend fun deleteTransfer(fileId: Long)
}
