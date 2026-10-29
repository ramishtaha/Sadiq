package com.ramish.sadiq

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application object, and the one place startup work is run.
 *
 * Features register an [AppInitializer] in their own Hilt module, so nothing has to be added here when
 * a feature ships: this class only starts whatever is registered.
 */
@HiltAndroidApp
class SadiqApp : Application() {

    /**
     * Every registered initializer. Hilt contributes these from each feature's `@IntoSet` binding; the
     * set is empty until features exist, which is why `ShellModule` declares it `@Multibinds`.
     */
    @Inject
    lateinit var initializers: Set<@JvmSuppressWildcards AppInitializer>

    override fun onCreate() {
        super.onCreate()
        // A set has no defined order, and two initializers can touch the same component (the
        // accessibility service, the session service). Sorting by class name keeps the order stable
        // between launches, so a startup bug reproduces instead of depending on the hash of the day.
        initializers
            .sortedBy { it.javaClass.name }
            .forEach { it.start() }
    }
}
