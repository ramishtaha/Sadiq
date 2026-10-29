package com.ramish.sadiq.ui.ramadan

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import javax.inject.Inject

/**
 * `Routes.RAMADAN`: the Ramadan screen.
 *
 * Registered here for the same reason as the strict screen: the route is already reserved, and
 * keeping the entry with the screen means a settings row or the home card can open it without any
 * other package listing it.
 */
class RamadanFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = Routes.RAMADAN

    override val tab: Tab? = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        RamadanSettingsScreen(nav)
    }
}
