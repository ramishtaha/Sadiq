package com.ramish.sadiq.di

import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.notify.AsleepWatch
import com.ramish.sadiq.notify.LiveNotifierImpl
import com.ramish.sadiq.notify.NotificationInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * WP-11's bindings: the [LiveNotifier] the effect router talks to, and the initializer that creates
 * the three channels before anything can post.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyModule {

    @Binds
    @Singleton
    abstract fun liveNotifier(impl: LiveNotifierImpl): LiveNotifier

    @Binds
    @IntoSet
    abstract fun notificationInitializer(impl: NotificationInitializer): AppInitializer

    /** The one note for a switch that is on while nothing is bound (A5-07). */
    @Binds
    @IntoSet
    abstract fun asleepWatch(impl: AsleepWatch): AppInitializer
}
