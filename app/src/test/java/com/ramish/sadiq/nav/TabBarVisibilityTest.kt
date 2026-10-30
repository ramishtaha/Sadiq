package com.ramish.sadiq.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A4-15: the tab bar must not be drawn inside the wizard, and must be drawn everywhere else.
 *
 * The rule is pure ([showsTabBar]), so this runs on the JVM with no `NavController`.
 */
class TabBarVisibilityTest {

    @Test
    fun theWizard_hasNoTabBar() {
        assertFalse(showsTabBar(currentRoute = Routes.ONBOARDING, tabCount = 4))
    }

    @Test
    fun everyTab_showsTheTabBar() {
        listOf(Routes.SKY, Routes.FOCUS, Routes.GUARD, Routes.WEEK).forEach { route ->
            assertTrue(showsTabBar(currentRoute = route, tabCount = 4), "no bar on $route")
        }
    }

    /** A secondary screen keeps the bar: it is a place the user can leave by tapping a tab. */
    @Test
    fun aSecondaryScreen_keepsTheTabBar() {
        listOf(Routes.SETTINGS, Routes.HEALTH, Routes.MODES, Routes.ABOUT).forEach { route ->
            assertTrue(showsTabBar(currentRoute = route, tabCount = 4), "no bar on $route")
        }
    }

    @Test
    fun aShellWithNoTabs_drawsNoBar() {
        assertFalse(showsTabBar(currentRoute = Routes.SKY, tabCount = 0))
        assertFalse(showsTabBar(currentRoute = Routes.ONBOARDING, tabCount = 0))
    }

    /**
     * The first composition has no back-stack entry yet. The bar is drawn for that one frame rather
     * than waiting: the start destination is always a tab, so the alternative is a layout that jumps
     * after the first frame on every launch.
     */
    @Test
    fun anUnsettledRoute_drawsTheBar() {
        assertTrue(showsTabBar(currentRoute = null, tabCount = 4))
    }
}
