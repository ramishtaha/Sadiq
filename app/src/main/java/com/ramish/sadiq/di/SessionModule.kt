package com.ramish.sadiq.di

import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.session.SessionServiceStarter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * WP-16's wiring: the one piece of the session feature that the graph has to be told about.
 *
 * `SessionService` is created by the system and injected by `@AndroidEntryPoint`;
 * `NotificationActionReceiver` reaches the graph through `SessionEntryPoint`; `FocusAlarm` and
 * `SessionServiceStarter` are `@Inject` singletons with nothing to choose between implementations.
 * What is left is the startup hook, contributed into the `Set<AppInitializer>` seam so the service
 * starts keeping time at process start without `SadiqApp` naming it (§5.0 rule 3).
 *
 * Nothing binds [com.ramish.sadiq.session.FgsPolicy]: it is a pure object with no state and no
 * dependencies, and handing it out through the graph would only make it look replaceable.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @IntoSet
    abstract fun sessionStarter(impl: SessionServiceStarter): AppInitializer
}
