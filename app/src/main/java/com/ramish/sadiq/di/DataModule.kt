package com.ramish.sadiq.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.ramish.sadiq.contract.EngineSnapshotStore
import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.StarRepository
import com.ramish.sadiq.contract.UiPrefsSource
import com.ramish.sadiq.data.db.ConfigDao
import com.ramish.sadiq.data.db.HistoryDao
import com.ramish.sadiq.data.db.PendingChangeDao
import com.ramish.sadiq.data.db.SadiqDatabase
import com.ramish.sadiq.data.db.StarDao
import com.ramish.sadiq.data.engine.EngineSnapshotStoreImpl
import com.ramish.sadiq.data.engine.EngineStateJsonSerializer
import com.ramish.sadiq.data.repo.HistoryRepositoryImpl
import com.ramish.sadiq.data.repo.PendingChangesRepositoryImpl
import com.ramish.sadiq.data.repo.StarRepositoryImpl
import com.ramish.sadiq.data.settings.SettingsKeys
import com.ramish.sadiq.data.settings.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The data layer's Hilt module: one Room database, two DataStore files and the bindings behind the
 * WP-02 repository interfaces.
 *
 * `SettingsRepositoryImpl` is bound twice — as [SettingsRepository] for writers and as
 * [UiPrefsSource] for readers. The implementation is `@Singleton`, so both names resolve to the same
 * instance and therefore to the same hot config.
 *
 * There is no `fallbackToDestructiveMigration` here, by rule: a schema change needs a migration.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindUiPrefsSource(impl: SettingsRepositoryImpl): UiPrefsSource

    @Binds
    abstract fun bindStarRepository(impl: StarRepositoryImpl): StarRepository

    @Binds
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    abstract fun bindPendingChangesRepository(impl: PendingChangesRepositoryImpl): PendingChangesRepository

    @Binds
    abstract fun bindEngineSnapshotStore(impl: EngineSnapshotStoreImpl): EngineSnapshotStore

    companion object {

        @Provides
        @Singleton
        fun sadiqDatabase(@ApplicationContext context: Context): SadiqDatabase =
            Room.databaseBuilder(context, SadiqDatabase::class.java, SadiqDatabase.NAME).build()

        @Provides
        @Singleton
        fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // A corrupt preferences file must not stop the first launch: fall back to defaults,
                // which is also what a fresh install looks like.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile(SettingsKeys.STORE_NAME) },
            )

        @Provides
        @Singleton
        fun engineStateDataStore(@ApplicationContext context: Context): DataStore<String> =
            DataStoreFactory.create(
                serializer = EngineStateJsonSerializer,
                // A corrupt snapshot is equivalent to no snapshot; load() then returns null.
                corruptionHandler = ReplaceFileCorruptionHandler { "" },
                produceFile = { File(context.filesDir, EngineSnapshotStoreImpl.FILE_NAME) },
            )

        @Provides
        fun starDao(database: SadiqDatabase): StarDao = database.starDao()

        @Provides
        fun historyDao(database: SadiqDatabase): HistoryDao = database.historyDao()

        @Provides
        fun configDao(database: SadiqDatabase): ConfigDao = database.configDao()

        @Provides
        fun pendingChangeDao(database: SadiqDatabase): PendingChangeDao = database.pendingChangeDao()
    }
}
