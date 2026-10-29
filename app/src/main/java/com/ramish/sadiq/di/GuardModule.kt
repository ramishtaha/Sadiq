package com.ramish.sadiq.di

import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.contract.MediaDucker
import com.ramish.sadiq.contract.SurfacePresenter
import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.economy.StarEconomy
import com.ramish.sadiq.core.engine.GuardEngine
import com.ramish.sadiq.core.engine.GuardReducer
import com.ramish.sadiq.core.insight.DefaultNextActionSuggester
import com.ramish.sadiq.core.insight.DefaultReflectionPolicy
import com.ramish.sadiq.core.schedule.AdhanPrayerTimesProvider
import com.ramish.sadiq.core.schedule.DefaultScheduleEvaluator
import com.ramish.sadiq.core.schedule.PrayerTimesProvider
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import com.ramish.sadiq.core.sky.ConstellationRule
import com.ramish.sadiq.core.sky.QuietNightConstellations
import com.ramish.sadiq.core.sky.StarLayout
import com.ramish.sadiq.core.sky.VogelStarLayout
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.guard.ContextInterceptLauncher
import com.ramish.sadiq.guard.EffectRouter
import com.ramish.sadiq.guard.GuardControllerImpl
import com.ramish.sadiq.guard.HomeActionImpl
import com.ramish.sadiq.guard.InterceptLauncher
import com.ramish.sadiq.guard.MediaDuckerImpl
import com.ramish.sadiq.guard.SurfacePresenterImpl
import com.ramish.sadiq.guard.SystemClockSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The engine's single thread: the input consumer, its deadline timers and its snapshot writer.
 *
 * A dedicated qualifier rather than a bare `CoroutineDispatcher` because the whole design depends on
 * every engine step running on the *same* thread — an accidental second consumer on `Dispatchers.IO`
 * would be a data race the reducer could not see.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class GuardLoop

/**
 * The engine's background work: effect dispatch, the overlay fallback timer, the usage query.
 *
 * Multi-threaded on purpose. None of it may touch engine state, and all of it may block (a Room
 * write, starting an activity, a UsageStats query), so it must not share the single engine thread.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class GuardWork

/**
 * WP-13's bindings: the pure engine, the objects that drive it, and the platform seams the effect
 * router talks to (§3.5).
 *
 * Everything the engine needs is provided here and nowhere else, so the graph of the guard feature
 * can be read in one file: the reducer with its three pure collaborators, the schedule evaluator
 * over Adhan, the star sky's layout and constellation rules, the four platform actions, and the
 * clock. The concrete classes (`GuardControllerImpl`, `EffectRouter`, `Recorder`,
 * `ContextAssembler`, `InterceptStateHolder`, `GuardServiceHandle`) are `@Inject`-constructed
 * singletons and need no line here.
 *
 * [GuardStartup] is contributed into the `Set<AppInitializer>` shell seam, which is how the engine
 * comes alive at process start without `SadiqApp` knowing it exists.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GuardModule {

    @Binds
    @Singleton
    abstract fun guardController(impl: GuardControllerImpl): GuardController

    @Binds
    @Singleton
    abstract fun surfacePresenter(impl: SurfacePresenterImpl): SurfacePresenter

    @Binds
    @Singleton
    abstract fun interceptLauncher(impl: ContextInterceptLauncher): InterceptLauncher

    @Binds
    @Singleton
    abstract fun homeAction(impl: HomeActionImpl): HomeAction

    @Binds
    @Singleton
    abstract fun mediaDucker(impl: MediaDuckerImpl): MediaDucker

    @Binds
    @Singleton
    abstract fun clock(impl: SystemClockSource): Clock

    @Binds
    @IntoSet
    abstract fun guardStartup(impl: GuardStartup): AppInitializer

    companion object {

        /**
         * The star economy. Stateless — every rule reads the day counters it is handed — so one
         * instance serves the reducer and any screen that projects a focus session's stars.
         */
        @Provides
        @Singleton
        fun starEconomy(): StarEconomy = DefaultStarEconomy()

        /**
         * The reducer, with the economy above rather than a second copy of it: the engine awards the
         * same stars the Focus screen projects.
         */
        @Provides
        @Singleton
        fun guardReducer(economy: StarEconomy): GuardReducer = GuardEngine(
            economy = economy,
            reflection = DefaultReflectionPolicy(),
            suggester = DefaultNextActionSuggester(),
        )

        /** Adhan 1.2.1, Karachi/Hanafi, Thane defaults: the same times Rafiq computes (§3.5). */
        @Provides
        @Singleton
        fun prayerTimes(): PrayerTimesProvider = AdhanPrayerTimesProvider()

        @Provides
        @Singleton
        fun scheduleEvaluator(prayerTimes: PrayerTimesProvider): ScheduleEvaluator =
            DefaultScheduleEvaluator(prayerTimes)

        /** Deterministic star positions; the seed comes from the preferences. */
        @Provides
        @Singleton
        fun starLayout(): StarLayout = VogelStarLayout()

        /** Every seventh quiet night is joined by faint lines (§3.8). */
        @Provides
        @Singleton
        fun constellationRule(): ConstellationRule = QuietNightConstellations()

        /** One thread for the whole engine: the single-consumer guarantee of §3.5, in one binding. */
        @Provides
        @Singleton
        @GuardLoop
        fun guardLoop(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

        /** Background work for the effects, the fallback timer and the usage query. */
        @Provides
        @Singleton
        @GuardWork
        fun guardWork(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/**
 * Starts the engine and its effect pipeline once the process is up.
 *
 * Registered through the `Set<AppInitializer>` seam, so `SadiqApp` runs it without a line of code
 * naming the guard. The router subscribes first: an effect emitted before anything is listening
 * would be dropped, and the first prompt of the process is exactly the one that must not be.
 *
 * It lives in this file because the module is the only place WP-13 owns outside `guard/`, and the
 * startup hook is part of the module's wiring rather than a class any other code refers to.
 */
@Singleton
class GuardStartup @Inject constructor(
    private val controller: GuardControllerImpl,
    private val effects: EffectRouter,
) : AppInitializer {

    override fun start() {
        effects.start()
        controller.start()
    }
}
