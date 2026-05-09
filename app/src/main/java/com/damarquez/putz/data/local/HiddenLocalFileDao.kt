package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HiddenLocalFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HiddenLocalFileEntity)

    @Delete
    suspend fun delete(entity: HiddenLocalFileEntity)

    @Query("SELECT uri FROM hidden_local_files")
    suspend fun getAllUris(): List<String>

    @Query("DELETE FROM hidden_local_files WHERE parentAttachmentId = :parentId")
    suspend fun deleteByParentId(parentId: Long)

    @Query("DELETE FROM hidden_local_files")
    suspend fun deleteAll()
}
