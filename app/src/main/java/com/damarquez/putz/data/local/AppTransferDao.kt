package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppTransferDao {

    @Query("SELECT * FROM app_transfers")
    suspend fun getAll(): List<AppTransferEntity>

    @Query("SELECT * FROM app_transfers WHERE putioId = :id")
    suspend fun getById(id: Long): AppTransferEntity?

    @Query("SELECT * FROM app_transfers WHERE infoHash = :hash LIMIT 1")
    suspend fun getByInfoHash(hash: String): AppTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppTransferEntity)

    @Query("DELETE FROM app_transfers WHERE putioId = :id")
    suspend fun deleteById(id: Long)

    /** Removes records whose put.io ID is no longer present in put.io's transfer list,
     *  unless they were intentionally stopped by the user. */
    @Query("DELETE FROM app_transfers WHERE isStopped = 0 AND putioId NOT IN (:activeIds)")
    suspend fun deleteStale(activeIds: List<Long>)

    @Query("DELETE FROM app_transfers")
    suspend fun deleteAll()
}
