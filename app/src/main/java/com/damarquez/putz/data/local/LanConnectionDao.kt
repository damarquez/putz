package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LanConnectionDao {
    @Query("SELECT * FROM lan_connections ORDER BY label ASC")
    fun getAll(): Flow<List<LanConnectionEntity>>

    @Query("SELECT * FROM lan_connections ORDER BY label ASC")
    suspend fun getAllSync(): List<LanConnectionEntity>

    @Query("SELECT * FROM lan_connections WHERE id = :id")
    suspend fun getById(id: Long): LanConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: LanConnectionEntity): Long

    @Update
    suspend fun update(connection: LanConnectionEntity)

    @Query("DELETE FROM lan_connections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM lan_connections WHERE label = :label AND id != :excludeId")
    suspend fun countByLabelExcluding(label: String, excludeId: Long): Int
}
