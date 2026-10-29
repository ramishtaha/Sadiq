package com.ramish.sadiq.ui.guardtab

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class GuardEntryModule {
    @Binds
    @IntoSet
    abstract fun provideGuardFeatureEntry(entry: GuardEntry): FeatureEntry
}

class GuardEntry @Inject constructor(
    private val slots: Set<@JvmSuppressWildcards com.ramish.sadiq.contract.UiSlot>,
) : FeatureEntry {
    override val route = Routes.GUARD
    override val tab = Tab.GUARD

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        GuardScreen(
            navController = nav,
            slots = slots.filter { it.slot == com.ramish.sadiq.contract.SlotId.RULES_SECTION }.sortedBy { it.order },
        )
    }
}
