package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FolderDisplayNameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderDisplayNameEntity)

    @Query("SELECT * FROM folder_display_names")
    suspend fun getAll(): List<FolderDisplayNameEntity>

    @Query("SELECT displayName FROM folder_display_names WHERE putioFileId = :putioFileId")
    suspend fun getDisplayName(putioFileId: Long): String?

    @Query("DELETE FROM folder_display_names WHERE putioFileId = :putioFileId")
    suspend fun delete(putioFileId: Long)
}
