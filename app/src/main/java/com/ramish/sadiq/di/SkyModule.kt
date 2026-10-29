package com.ramish.sadiq.di

import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.ui.sky.AsleepCardProvider
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
}