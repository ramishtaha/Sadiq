package com.ramish.sadiq.ui.onboarding

import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.Tri
import com.ramish.sadiq.core.model.Mode

/**
 * The canonical plan of §4.7: a list of [StepId]s, in order.
 *
 * The list is derived from the current permissions, so a fresh install on a Pixel sees only 8
 * steps and a fully-granted Xiaomi sees only 1. Only the wizard itself cares about it.
 */
object OnboardingPlan {

    /**
     * One step in the wizard.
     *
     * A step is just an ID: its content is a composable and its logic is in the ViewModel. The VM
     * handles deep links and persisting progress, and the step ID itself is never persisted.
     *
     * It is nested in [OnboardingPlan] because the plan owns the order and every caller refers to a
     * step as `OnboardingPlan.StepId`.
     *
     * **The declaration order is the plan order.** The wizard compares steps by ordinal to find the
     * step that follows the one the user just finished, so reordering this list reorders the wizard.
     */
    enum class StepId {
        WELCOME,
        APPS,
        MODE,
        TIMES,
        ACCESSIBILITY,
        RESTRICTED_SETTING,
        NOTIFICATIONS,
        POPUPS,
        BATTERY_OPTIMIZATION,
        BATTERY_SAVER,
        AUTOSTART,
        RECENTS,
        USAGE,
        TRY_IT,
    }

    /**
     * The list of steps for the given [PermissionSnapshot].
     *
     * The conditions come straight from §4.7. The flow is strictly linear, with the exception of the
     * `RESTRICTED_SETTING` step, which follows `ACCESSIBILITY` once the user has been to the OS page
     * and the service is still off: that is the only case in which Android's "Restricted setting"
     * block can be the reason, and it is the only case in which O5b can help.
     *
     * @param perms what the permission reader last found.
     * @param isXiaomi whether the OEM-only steps apply.
     * @param accessibilityTried whether the user has already opened the accessibility page once. O5b
     *   exists only after that, so a user who has never seen the switch is never told about a block
     *   that may not exist.
     */
    fun steps(
        perms: PermissionSnapshot,
        isXiaomi: Boolean,
        accessibilityTried: Boolean = false,
    ): List<StepId> {
        val steps = mutableListOf<StepId>()

        steps.add(StepId.WELCOME)

        // O2: Pick apps, always shown
        steps.add(StepId.APPS)

        // O3: Mode, always shown
        steps.add(StepId.MODE)

        // O4: Nights and prayer, always shown
        steps.add(StepId.TIMES)

        // O5: Accessibility, and O5b when the user has been there and it is still off
        if (!perms.accessibilityEnabled || !perms.accessibilityConnected) {
            steps.add(StepId.ACCESSIBILITY)
            if (accessibilityTried) steps.add(StepId.RESTRICTED_SETTING)
        }

        // O6: Notifications
        if (!perms.notifications) {
            steps.add(StepId.NOTIFICATIONS)
        }

        // OEM steps
        if (isXiaomi) {
            // O7: Pop-ups
            if (perms.backgroundPopups == Tri.NO) {
                steps.add(StepId.POPUPS)
            }
            // O8: Battery, part 1
            if (!perms.batteryUnrestricted) {
                steps.add(StepId.BATTERY_OPTIMIZATION)
            }
            // O9: Battery, part 2
            // This needs to be shown even if perms.batteryUnrestricted is true
            // because the user needs to self-confirm.
            // We decide in the VM whether to skip this based on prefs.batterySaverConfirmed.
            steps.add(StepId.BATTERY_SAVER)

            // O10: Autostart
            // We decide in the VM whether to skip this based on prefs.autostartConfirmed.
            steps.add(StepId.AUTOSTART)

            // O11: Lock in Recents
            // We decide in the VM whether to skip this based on prefs.recentsLockedConfirmed.
            steps.add(StepId.RECENTS)
        }

        // O12: Usage access (skippable)
        if (!perms.usageAccess) {
            steps.add(StepId.USAGE)
        }

        // O13: Try it
        steps.add(StepId.TRY_IT)

        return steps
    }
}
