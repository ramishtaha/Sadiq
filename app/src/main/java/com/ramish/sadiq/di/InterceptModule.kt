package com.ramish.sadiq.di

import android.content.Context
import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.intercept.OverlayHost
import com.ramish.sadiq.ui.intercept.InterceptCopy
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The interception surface's bindings: its copy rules, and the overlay fallback's startup hook.
 *
 * The fallback is an [AppInitializer] rather than something the accessibility service calls, so it is
 * subscribed to `InterceptStateHolder` before the first question of the process can be asked — the
 * window has to be there for the first missed launch, not from the second one onwards (AUDIT-3 G-13).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InterceptModule {

    /** The overlay host, started with the process. It draws nothing until the presenter asks. */
    @Binds
    @IntoSet
    abstract fun overlayHost(impl: OverlayHost): AppInitializer

    companion object {

        @Provides
        @Singleton
        fun provideInterceptCopy(@ApplicationContext context: Context): InterceptCopy =
            InterceptCopy(context)
    }
}
