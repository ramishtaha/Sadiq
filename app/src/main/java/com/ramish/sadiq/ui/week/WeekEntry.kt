package com.ramish.sadiq.ui.week

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.core.insight.DefaultWeeklyReviewBuilder
import com.ramish.sadiq.core.insight.WeeklyReviewBuilder
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Week tab: registered like any other feature, so the bottom bar picks it up from
 * `Tab.WEEK`'s order and nothing in the shell learns that a fourth tab exists.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WeekEntryModule {

    @Binds
    @IntoSet
    abstract fun weekFeatureEntry(entry: WeekEntry): FeatureEntry
}

/**
 * The review maths, bound once.
 *
 * `DefaultWeeklyReviewBuilder` is a pure class in `core` with a defaulted constructor, so Hilt cannot
 * construct it on its own. It is built here with [AppCatalog]'s feed list — the same list the app
 * picker and onboarding use — so the "guard this feed" suggestion can never name a package the rest
 * of the app does not consider a feed.
 */
@Module
@InstallIn(SingletonComponent::class)
object WeekProviders {

    @Provides
    @Singleton
    fun weeklyReviewBuilder(): WeeklyReviewBuilder =
        DefaultWeeklyReviewBuilder(knownFeeds = AppCatalog.knownFeeds.toSet())
}

/** The tab's entry: route `week`, [Tab.WEEK], one screen. */
class WeekEntry @Inject constructor() : FeatureEntry {

    override val route: String = Routes.WEEK
    override val tab: Tab = Tab.WEEK

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        WeekScreen()
    }
}
