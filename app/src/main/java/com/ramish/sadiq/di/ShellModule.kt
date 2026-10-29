package com.ramish.sadiq.di

import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.nav.FeatureEntry
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * The only shared-file seam in the app. Features contribute to these sets from their own Hilt
 * modules; no work package ever edits this file again.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {
    @Multibinds
    abstract fun featureEntries(): Set<FeatureEntry>

    @Multibinds
    abstract fun appInitializers(): Set<AppInitializer>
}
