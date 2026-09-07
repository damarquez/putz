package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderSetDao {
    @Query("SELECT * FROM folder_set ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<FolderSetEntity>>

    @Query("SELECT * FROM folder_set ORDER BY addedAt ASC")
    suspend fun getAll(): List<FolderSetEntity>

    // IGNORE (not REPLACE): re-adding an id already in the set must be a no-op, not bump its
    // addedAt — otherwise a bulk "add selected" that included already-member folders would
    // silently reorder them.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entity: FolderSetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(entities: List<FolderSetEntity>)

    @Query("DELETE FROM folder_set WHERE putioFileId = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM folder_set")
    suspend fun clear()
}
