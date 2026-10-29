package com.ramish.sadiq.nav

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.ui.guardtab.AppPickerScreen
import com.ramish.sadiq.ui.guardtab.PrayerSettingsScreen
import com.ramish.sadiq.ui.guardtab.ProtectedTimesScreen
import dagger.Binds
import javax.inject.Inject
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class GuardTabFeatureEntryModule {
    @Binds
    @IntoSet
    abstract fun provideAppPickerEntry(entry: AppPickerEntry): FeatureEntry

    @Binds
    @IntoSet
    abstract fun providePrayerSettingsEntry(entry: PrayerSettingsEntry): FeatureEntry

    @Binds
    @IntoSet
    abstract fun provideProtectedTimesEntry(entry: ProtectedTimesEntry): FeatureEntry
}

class AppPickerEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.APPS
    override val tab = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        AppPickerScreen(nav)
    }
}

class PrayerSettingsEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.PRAYER
    override val tab = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        PrayerSettingsScreen(nav)
    }
}

class ProtectedTimesEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.BEDTIME
    override val tab = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        ProtectedTimesScreen(nav)
    }
}
