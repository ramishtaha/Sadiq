package com.ramish.sadiq.ui.strict

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ramish.sadiq.nav.FeatureEntry
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.nav.Tab
import javax.inject.Inject

/**
 * `Routes.STRICT`: the full-screen "Think it over".
 *
 * Registered from this package rather than from `nav/`, because the route already exists in
 * [Routes] and this screen owns its own copy. It is how a pending change can be inspected, and how
 * a future settings row can open the feature without adding a destination.
 */
class StrictFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = Routes.STRICT

    override val tab: Tab? = null

    @Composable
    override fun Content(nav: NavHostController, args: Bundle?) {
        StrictSettingsScreen(nav)
    }
}
