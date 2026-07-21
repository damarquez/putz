package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingResponseDeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingResponseDeletionEntity)

    @Query("SELECT fileId FROM pending_response_deletions")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM pending_response_deletions WHERE fileId = :fileId")
    suspend fun deleteById(fileId: String)
}
