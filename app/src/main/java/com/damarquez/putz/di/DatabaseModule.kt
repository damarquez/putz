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

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN calibreBookUuid TEXT"
        )
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN lastRequestPayload TEXT"
        )
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN localUrisJson TEXT"
        )
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `lan_connections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, `host` TEXT NOT NULL, `username` TEXT NOT NULL, `shareName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_lan_connections_label` ON `lan_connections` (`label`)"
        )
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN warnings TEXT"
        )
    }
}

private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN transferType TEXT NOT NULL DEFAULT 'CALIBRE'"
        )
    }
}

private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN libraryVerified INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN calibreBookId INTEGER"
        )
    }
}

private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN tags TEXT"
        )
    }
}

private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN probeCount INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `magnetOrUrl` TEXT NOT NULL, `saveParentId` INTEGER NOT NULL, `infoHash` TEXT, `displayNameHint` TEXT, `addedAt` INTEGER NOT NULL)"
        )
    }
}

// Locally-queued transfer adds are now tracked via the shared transfer-history daemon
// (status QUEUED_OUTSIDE_PUTIO) instead of a per-device table, so this table is dropped.
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS `pending_transfers`")
    }
}

private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE app_transfers ADD COLUMN saveParentId INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN hasPutioFile INTEGER NOT NULL DEFAULT 1"
        )
    }
}

private val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE app_transfers ADD COLUMN manuallyRenamed INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN priority INTEGER NOT NULL DEFAULT 0"
        )
    }
}

private val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE calibre_transfers ADD COLUMN durationSeconds REAL"
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
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26)
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

    @Provides
    fun provideLanConnectionDao(db: PutzDatabase) = db.lanConnectionDao()
}
