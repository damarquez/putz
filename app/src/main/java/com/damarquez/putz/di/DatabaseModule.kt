package com.damarquez.putz.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.damarquez.putz.data.local.AppTransferDao
import com.damarquez.putz.data.local.PutzDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN allPutioFileIds TEXT NOT NULL DEFAULT ''"
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE app_transfers ADD COLUMN percentDone INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE app_transfers ADD COLUMN size INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` INTEGER NOT NULL, `isFolder` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN isTempUpload INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `hidden_local_files` (`uri` TEXT NOT NULL, `parentAttachmentId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
        )
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN sourceLocalUri TEXT"
        )
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN batchData TEXT"
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PutzDatabase =
        Room.databaseBuilder(context, PutzDatabase::class.java, "putz.db")
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAppTransferDao(db: PutzDatabase): AppTransferDao = db.appTransferDao()

    @Provides
    fun provideCalibreTransferDao(db: PutzDatabase) = db.calibreTransferDao()

    @Provides
    fun provideLocalAttachmentDao(db: PutzDatabase) = db.localAttachmentDao()

    @Provides
    fun provideHiddenLocalFileDao(db: PutzDatabase) = db.hiddenLocalFileDao()
}
