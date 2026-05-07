package com.damarquez.putz.di

import android.content.Context
import androidx.room.Room
import com.damarquez.putz.data.local.AppTransferDao
import com.damarquez.putz.data.local.PutzDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PutzDatabase =
        Room.databaseBuilder(context, PutzDatabase::class.java, "putz.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAppTransferDao(db: PutzDatabase): AppTransferDao = db.appTransferDao()

    @Provides
    fun provideCalibreTransferDao(db: PutzDatabase) = db.calibreTransferDao()
}
