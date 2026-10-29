package com.ramish.sadiq.ui.focus

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import dagger.Binds
import javax.inject.Inject
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class FocusEntryModule {
    @Binds
    @IntoSet
    abstract fun provideFocusFeatureEntry(entry: FocusEntry): FeatureEntry
}

class FocusEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.FOCUS
    override val tab = Tab.FOCUS

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        FocusScreen(nav)
    }
}