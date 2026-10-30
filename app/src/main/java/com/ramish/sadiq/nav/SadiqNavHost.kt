package com.ramish.sadiq.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * The shell: a scaffold, a bottom bar built from the registered tab entries, and a graph built from
 * every registered [FeatureEntry]. Nothing else knows the list of screens.
 *
 * The bar is not drawn inside the wizard (see [showsTabBar]): setup is a task with its own exits, and
 * a tab tap in the middle of it used to abandon the flow for good.
 */
@Composable
fun SadiqNavHost(
    entries: Set<FeatureEntry>,
    modifier: Modifier = Modifier
) {
    val nav = rememberNavController()
    val ordered = remember(entries) {
        entries.filter { it.tab != null }.sortedBy { it.tab!!.order }
    }
    val startDestination = ordered.firstOrNull()?.route ?: Routes.SKY
    val registered = remember(entries) { entries.map { it.route }.toSet() }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showsTabBar(currentRoute, ordered.size)) {
                SadiqBottomBar(nav = nav, entries = ordered)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            entries.forEach { entry ->
                composable(entry.route) { entry.Content(nav, null) }
            }
            if (Routes.SKY !in registered) {
                composable(Routes.SKY) { PlaceholderScreen() }
            }
        }
    }
}
