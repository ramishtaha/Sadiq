package com.ramish.sadiq.ui.onboarding

import androidx.annotation.StringRes
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.onboarding.OnboardingPlan.StepId

/**
 * The exact HyperOS menu path each OEM step shows under its body.
 *
 * The copy already existed in `strings_onboarding_oem.xml` and no step rendered it, so a user was
 * told to open a page and left to find the switch. HyperOS renames these menus between versions and
 * the Poco F7 may run either, so the choice is a function of the detected major.
 *
 * Two are versioned outright: the accessibility list is "Downloaded apps" on HyperOS 2 and
 * "Installed apps" on HyperOS 3 ([R.string.onboarding_os_o5_os2_path] versus
 * [R.string.onboarding_os_o5_os3_path]). O5b's "Allow restricted settings" and O12's usage-access
 * list were not renamed between the two versions, so one string serves both
 * ([R.string.onboarding_os_o5b_path], [R.string.onboarding_os_o12_path]).
 *
 * The remaining two carry the variant in the name only as `_path` / `_alt_path`: `_path` is the
 * MIUI-lineage wording the HyperOS 2 builds kept, `_alt_path` the alternative the plan records for
 * newer builds ("Open new windows…" for pop-ups, Autostart inside App info). That assignment is
 * **unverified** — it is the one thing on this screen that needs a minute on his phone — but it can
 * only ever be wrong in the label, never in the destination: the deep link does not depend on it.
 *
 * Nothing is rendered for an unknown version. A MIUI build reports no HyperOS major at all, and a
 * path with the wrong menu names is worse than no path: the action button still opens the page, and
 * the user reads the menu on the page itself.
 */
object OemPaths {

    /** HyperOS 2, the MIUI-lineage menus. */
    private const val HYPER_OS_2: Int = 2

    /** HyperOS 3, whose settings app renamed two of these entries. */
    private const val HYPER_OS_3: Int = 3

    /**
     * The path string for [step] on [hyperOsMajor], or null when there is none to show.
     *
     * @param step the OEM step being drawn.
     * @param hyperOsMajor the detected HyperOS major, or null when unknown.
     */
    @StringRes
    fun pathFor(step: StepId, hyperOsMajor: Int?): Int? = when (hyperOsMajor) {
        HYPER_OS_2 -> when (step) {
            StepId.ACCESSIBILITY -> R.string.onboarding_os_o5_os2_path
            StepId.RESTRICTED_SETTING -> R.string.onboarding_os_o5b_path
            StepId.POPUPS -> R.string.onboarding_os_o7_path
            StepId.BATTERY_SAVER -> R.string.onboarding_os_o9_path
            StepId.AUTOSTART -> R.string.onboarding_os_o10_path
            StepId.RECENTS -> R.string.onboarding_os_o11_path
            StepId.USAGE -> R.string.onboarding_os_o12_path
            else -> null
        }

        HYPER_OS_3 -> when (step) {
            StepId.ACCESSIBILITY -> R.string.onboarding_os_o5_os3_path
            StepId.RESTRICTED_SETTING -> R.string.onboarding_os_o5b_path
            StepId.POPUPS -> R.string.onboarding_os_o7_alt_path
            StepId.BATTERY_SAVER -> R.string.onboarding_os_o9_path
            StepId.AUTOSTART -> R.string.onboarding_os_o10_alt_path
            StepId.RECENTS -> R.string.onboarding_os_o11_path
            StepId.USAGE -> R.string.onboarding_os_o12_path
            else -> null
        }

        // Unknown, MIUI, or a major nobody has read the menus on yet: say nothing rather than guess.
        else -> null
    }
}
