package com.damarquez.putz.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppTransferEntity::class,
        CalibreTransferEntity::class,
        LocalAttachmentEntity::class,
        HiddenLocalFileEntity::class,
        LanConnectionEntity::class,
    ],
    version = 25,
    exportSchema = false,
)
abstract class PutzDatabase : RoomDatabase() {
    abstract fun appTransferDao(): AppTransferDao
    abstract fun calibreTransferDao(): CalibreTransferDao
    abstract fun localAttachmentDao(): LocalAttachmentDao
    abstract fun hiddenLocalFileDao(): HiddenLocalFileDao
    abstract fun lanConnectionDao(): LanConnectionDao
}
