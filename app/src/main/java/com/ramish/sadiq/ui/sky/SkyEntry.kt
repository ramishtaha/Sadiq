package com.ramish.sadiq.ui.sky

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

@Module
@InstallIn(SingletonComponent::class)
abstract class SkyEntryModule {
    @Binds
    @IntoSet
    abstract fun provideSkyFeatureEntry(entry: SkyEntry): FeatureEntry
}

class SkyEntry @Inject constructor() : FeatureEntry {
    override val route = Routes.SKY
    override val tab = Tab.SKY

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        SkyScreen(nav)
    }
}
