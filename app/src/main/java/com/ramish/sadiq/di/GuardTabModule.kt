package com.ramish.sadiq.di

import android.content.Context
import com.ramish.sadiq.contract.UiSlot
import com.ramish.sadiq.nav.GuardTabFeatureEntryModule
import com.ramish.sadiq.ui.guardtab.GuardEntryModule
import com.ramish.sadiq.ui.guardtab.OneShotLocation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module(includes = [GuardEntryModule::class, GuardTabFeatureEntryModule::class])
@InstallIn(SingletonComponent::class)
object GuardTabModule {
    // UI slots will be bound here if any are owned by this WP

    @Provides
    @Singleton
    fun provideOneShotLocation(@ApplicationContext context: Context): OneShotLocation {
        return OneShotLocation(context)
    }
}

