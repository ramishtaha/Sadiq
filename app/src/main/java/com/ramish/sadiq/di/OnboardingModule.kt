package com.ramish.sadiq.di

import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.ui.onboarding.OnboardingEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Onboarding's Hilt module: it binds its [FeatureEntry] into the shared set.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModule {

    @Binds
    @IntoSet
    abstract fun bindOnboardingEntry(entry: OnboardingEntry): FeatureEntry
}
