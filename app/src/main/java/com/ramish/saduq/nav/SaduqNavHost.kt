package com.ramish.saduq.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * The shell: a scaffold, a bottom bar built from the registered tab entries, and a graph built from
 * every registered [FeatureEntry]. Nothing else knows the list of screens.
 */
@Composable
fun SaduqNavHost(
    entries: Set<FeatureEntry>,
    modifier: Modifier = Modifier
) {
    val nav = rememberNavController()
    val ordered = remember(entries) {
        entries.filter { it.tab != null }.sortedBy { it.tab!!.order }
    }
    val startDestination = ordered.firstOrNull()?.route ?: Routes.SKY
    val registered = remember(entries) { entries.map { it.route }.toSet() }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (ordered.isNotEmpty()) {
                SaduqBottomBar(nav = nav, entries = ordered)
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
