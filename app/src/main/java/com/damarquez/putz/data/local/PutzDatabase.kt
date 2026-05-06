package com.damarquez.putz.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppTransferEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PutzDatabase : RoomDatabase() {
    abstract fun appTransferDao(): AppTransferDao
}
