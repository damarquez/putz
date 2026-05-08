package com.damarquez.putz.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppTransferEntity::class, CalibreTransferEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class PutzDatabase : RoomDatabase() {
    abstract fun appTransferDao(): AppTransferDao
    abstract fun calibreTransferDao(): CalibreTransferDao
}
