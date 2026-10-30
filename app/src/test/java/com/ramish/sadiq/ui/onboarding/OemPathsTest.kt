package com.ramish.sadiq.ui.onboarding

import com.ramish.sadiq.R
import com.ramish.sadiq.ui.onboarding.OnboardingPlan.StepId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A4-15 and A5-13: the HyperOS menu paths, and the two the wizard used to leave out.
 *
 * These assert the mapping only — that the string exists for a version and a step — not that the
 * wording is right on a real phone. That is the one part nobody can check from here (§4.7 says the
 * labels drift between HyperOS 2 and 3, and the Poco F7 may run either).
 */
class OemPathsTest {

    private val oemSteps = listOf(
        StepId.ACCESSIBILITY,
        StepId.RESTRICTED_SETTING,
        StepId.POPUPS,
        StepId.BATTERY_SAVER,
        StepId.AUTOSTART,
        StepId.RECENTS,
        StepId.USAGE,
    )

    @Test
    fun hyperOs2_usesTheOs2Menus() {
        assertEquals(R.string.onboarding_os_o5_os2_path, OemPaths.pathFor(StepId.ACCESSIBILITY, 2))
        assertEquals(R.string.onboarding_os_o5b_path, OemPaths.pathFor(StepId.RESTRICTED_SETTING, 2))
        assertEquals(R.string.onboarding_os_o7_path, OemPaths.pathFor(StepId.POPUPS, 2))
        assertEquals(R.string.onboarding_os_o9_path, OemPaths.pathFor(StepId.BATTERY_SAVER, 2))
        assertEquals(R.string.onboarding_os_o10_path, OemPaths.pathFor(StepId.AUTOSTART, 2))
        assertEquals(R.string.onboarding_os_o11_path, OemPaths.pathFor(StepId.RECENTS, 2))
        assertEquals(R.string.onboarding_os_o12_path, OemPaths.pathFor(StepId.USAGE, 2))
    }

    @Test
    fun hyperOs3_usesTheOtherSet() {
        assertEquals(R.string.onboarding_os_o5_os3_path, OemPaths.pathFor(StepId.ACCESSIBILITY, 3))
        assertEquals(R.string.onboarding_os_o5b_path, OemPaths.pathFor(StepId.RESTRICTED_SETTING, 3))
        assertEquals(R.string.onboarding_os_o7_alt_path, OemPaths.pathFor(StepId.POPUPS, 3))
        assertEquals(R.string.onboarding_os_o9_path, OemPaths.pathFor(StepId.BATTERY_SAVER, 3))
        assertEquals(R.string.onboarding_os_o10_alt_path, OemPaths.pathFor(StepId.AUTOSTART, 3))
        assertEquals(R.string.onboarding_os_o11_path, OemPaths.pathFor(StepId.RECENTS, 3))
        assertEquals(R.string.onboarding_os_o12_path, OemPaths.pathFor(StepId.USAGE, 3))
    }

    @Test
    fun theTwoNewPaths_areNotVersioned() {
        // A5-13: "Allow restricted settings" and the usage-access list were not renamed between 2
        // and 3, so one string serves both — and it must exist on both.
        listOf(StepId.RESTRICTED_SETTING, StepId.USAGE).forEach { step ->
            assertEquals(OemPaths.pathFor(step, 2), OemPaths.pathFor(step, 3), "$step is not versioned")
        }
    }

    @Test
    fun hyperOs3_doesNotReuseTheOs2AccessibilityPath() {
        // "Downloaded apps" on OS2, "Installed apps" on OS3: the two must not collapse to one string.
        assertNotEquals(
            OemPaths.pathFor(StepId.ACCESSIBILITY, 2),
            OemPaths.pathFor(StepId.ACCESSIBILITY, 3),
        )
    }

    @Test
    fun anUnknownVersion_rendersNothing() {
        // null is MIUI or an unparseable build; 1 and 4 are versions whose menus nobody has read.
        listOf(null, 1, 4).forEach { major ->
            oemSteps.forEach { step ->
                assertNull(OemPaths.pathFor(step, major), "a path was drawn for $step on HyperOS $major")
            }
        }
    }

    @Test
    fun everyOemStep_hasAPathOnBothVersions() {
        listOf(2, 3).forEach { major ->
            oemSteps.forEach { step ->
                assertNotNull(OemPaths.pathFor(step, major), "no path for $step on HyperOS $major")
            }
        }
    }

    @Test
    fun stepsThatAreNotOemSteps_renderNoPath() {
        listOf(
            StepId.WELCOME,
            StepId.APPS,
            StepId.MODE,
            StepId.TIMES,
            StepId.NOTIFICATIONS,
            StepId.BATTERY_OPTIMIZATION,
            StepId.TRY_IT,
        ).forEach { step ->
            listOf(2, 3).forEach { major ->
                assertNull(OemPaths.pathFor(step, major), "$step should not show a menu path")
            }
        }
    }
}
