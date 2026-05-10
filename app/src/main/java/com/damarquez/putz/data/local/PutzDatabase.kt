package com.damarquez.putz.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppTransferEntity::class, 
        CalibreTransferEntity::class,
        LocalAttachmentEntity::class,
        HiddenLocalFileEntity::class
    ],
    version = 9,
    exportSchema = false,
)
abstract class PutzDatabase : RoomDatabase() {
    abstract fun appTransferDao(): AppTransferDao
    abstract fun calibreTransferDao(): CalibreTransferDao
    abstract fun localAttachmentDao(): LocalAttachmentDao
    abstract fun hiddenLocalFileDao(): HiddenLocalFileDao
}
