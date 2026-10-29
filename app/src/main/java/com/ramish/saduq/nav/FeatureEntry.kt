package com.ramish.saduq.nav

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

/**
 * A screen contributed to the graph. Screens never edit the NavHost: they inject themselves into
 * `Set<FeatureEntry>` and the host renders whatever is registered.
 *
 * @property route one of [Routes].
 * @property tab non-null only for the four bottom-bar destinations.
 */
interface FeatureEntry {
    val route: String
    val tab: Tab?

    @Composable
    fun Content(nav: NavHostController, args: Bundle?)
}
