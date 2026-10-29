package com.ramish.sadiq.ui.settings

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/**
 * The three screens that had routes but no entries.
 *
 * The Sky gear opens [Routes.SETTINGS] and Guard → "Your morning" opens [Routes.MORNING]; both threw
 * `IllegalArgumentException` because nothing was registered at those names. [Routes.HEALTH] is the
 * screen Settings → Health check opens.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsFeatureEntryModule {
    @Binds
    @IntoSet
    abstract fun provideSettingsEntry(entry: SettingsEntry): FeatureEntry

    @Binds
    @IntoSet
    abstract fun provideHealthEntry(entry: HealthEntry): FeatureEntry

    @Binds
    @IntoSet
    abstract fun provideMorningEntry(entry: MorningEntry): FeatureEntry
}

/** S5: the Settings screen, behind the Sky gear. */
class SettingsEntry @Inject constructor(
    private val slots: Set<@JvmSuppressWildcards com.ramish.sadiq.contract.UiSlot>,
) : FeatureEntry {
    override val route = Routes.SETTINGS
    override val tab = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        SettingsScreen(
            nav = nav,
            slots = slots.filter { it.slot == com.ramish.sadiq.contract.SlotId.SETTINGS_SECTION }.sortedBy { it.order },
        )
    }
}

/** S6: the Health check, behind Settings → Health check. */
class HealthEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.HEALTH
    override val tab = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        HealthCheckScreen(nav)
    }
}

/** The morning window, behind Guard → "Your morning". */
class MorningEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.MORNING
    override val tab = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        MorningWindowScreen(nav)
    }
}
