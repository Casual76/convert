package com.p2r3.convert.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.p2r3.convert.data.bridge.CompatibilityBridge
import com.p2r3.convert.data.bridge.WebViewCompatibilityBridge
import com.p2r3.convert.data.engine.CompositeConversionEngine
import com.p2r3.convert.data.engine.ConversionEngine
import com.p2r3.convert.data.history.ConvertDatabase
import com.p2r3.convert.data.history.HistoryDao
import com.p2r3.convert.data.history.HistoryRepository
import com.p2r3.convert.data.history.RoomHistoryRepository
import com.p2r3.convert.data.jobs.ConversionJobScheduler
import com.p2r3.convert.data.jobs.WorkManagerConversionJobScheduler
import com.p2r3.convert.data.preset.DefaultPresetRepository
import com.p2r3.convert.data.preset.PresetRepository
import com.p2r3.convert.data.settings.DataStoreSettingsRepository
import com.p2r3.convert.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: RoomHistoryRepository): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindPresetRepository(impl: DefaultPresetRepository): PresetRepository

    @Binds
    @Singleton
    abstract fun bindCompatibilityBridge(impl: WebViewCompatibilityBridge): CompatibilityBridge

    @Binds
    @Singleton
    abstract fun bindConversionEngine(impl: CompositeConversionEngine): ConversionEngine

    @Binds
    @Singleton
    abstract fun bindConversionJobScheduler(impl: WorkManagerConversionJobScheduler): ConversionJobScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ConvertDatabase =
        Room.databaseBuilder(context, ConvertDatabase::class.java, "convert-native.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideHistoryDao(database: ConvertDatabase): HistoryDao = database.historyDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
