package com.ramish.sadiq.ui.onboarding

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import javax.inject.Inject

/** The onboarding wizard is a top-level route; it takes no arguments and has no tab. */
class OnboardingEntry @Inject constructor() : FeatureEntry {
    override val route: String = Routes.ONBOARDING
    override val tab: Tab? = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        OnboardingScreen(nav)
    }
}
