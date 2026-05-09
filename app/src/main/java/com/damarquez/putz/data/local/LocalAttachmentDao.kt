package com.damarquez.putz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalAttachmentDao {

    @Query("SELECT * FROM local_attachments ORDER BY addedAt DESC")
    suspend fun getAll(): List<LocalAttachmentEntity>

    @Query("SELECT * FROM local_attachments WHERE parentId = :parentId ORDER BY name ASC")
    suspend fun getByParentId(parentId: Long): List<LocalAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalAttachmentEntity): Long

    @Query("DELETE FROM local_attachments WHERE id = :id")
    suspend fun deleteById(id: Long)
}
