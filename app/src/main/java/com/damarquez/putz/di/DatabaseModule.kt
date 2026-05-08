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

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PutzDatabase =
        Room.databaseBuilder(context, PutzDatabase::class.java, "putz.db")
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAppTransferDao(db: PutzDatabase): AppTransferDao = db.appTransferDao()

    @Provides
    fun provideCalibreTransferDao(db: PutzDatabase) = db.calibreTransferDao()
}
