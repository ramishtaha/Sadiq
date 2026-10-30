package com.ramish.sadiq.di

import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.ui.sky.AsleepCardProvider
import com.ramish.sadiq.ui.sky.PopupsMissCardProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class SkyModule {

    @Binds
    @IntoSet
    abstract fun asleepCardProvider(impl: AsleepCardProvider): HomeCardProvider

    /** A5-04: the question that never appeared, with the pop-ups page as its action. */
    @Binds
    @IntoSet
    abstract fun popupsMissCardProvider(impl: PopupsMissCardProvider): HomeCardProvider
}