package com.ramish.sadiq.ui.onboarding

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.Tri
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.data.settings.PrefsMapping
import com.ramish.sadiq.data.settings.SettingsKeys
import com.ramish.sadiq.ui.onboarding.OnboardingPlan.StepId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A4-15, second half: O5b has to survive HyperOS killing the process while the user is in Settings.
 *
 * The flags used to be `OnboardingViewModel` fields, so the kill erased them and the plan came back
 * without O5b. Here a `Preferences` map stands in for the DataStore: it is the only thing a new
 * process can see, and the plan is rebuilt from it exactly as `OnboardingViewModel.buildSteps` does
 * (the wizard additionally drops the steps the user skipped in that run, which these tests do not
 * model because nothing skips them).
 */
class OnboardingPlanPersistenceTest {

    /** A Xiaomi with everything granted except what a test sets, so the plan is short and readable. */
    private fun snapshot(
        accessibilityEnabled: Boolean = false,
        accessibilityConnected: Boolean = accessibilityEnabled,
        backgroundPopups: Tri = Tri.YES,
    ): PermissionSnapshot = PermissionSnapshot(
        accessibilityEnabled = accessibilityEnabled,
        accessibilityConnected = accessibilityConnected,
        notifications = true,
        usageAccess = true,
        batteryUnrestricted = true,
        isXiaomiFamily = true,
        hyperOsMajor = 2,
        backgroundPopups = backgroundPopups,
        autostart = Tri.YES,
        liveUpdates = Tri.NO,
        sdkInt = 35,
    )

    /** The plan for a set of stored preferences, as the wizard builds it after a fresh start. */
    private fun plan(store: MutablePreferences, perms: PermissionSnapshot = snapshot()): List<StepId> {
        val prefs: UiPrefs = PrefsMapping.readUiPrefs(store)
        return OnboardingPlan.steps(perms, perms.isXiaomiFamily, prefs.accessibilityTried)
    }

    @Test
    fun killedWhileInSettings_reopenedPlanStillHasO5b() {
        // 1. The user taps "Turn on Sadiq". The flag is written before the page opens, because the
        //    process may not survive the trip.
        val store: MutablePreferences = mutablePreferencesOf()
        PrefsMapping.writeUiPrefs(
            store,
            PrefsMapping.readUiPrefs(preferencesOf()).copy(accessibilityTried = true),
        )

        // 2. HyperOS kills Sadiq while the user is reading the accessibility list. Nothing in memory
        //    survives; a new process reads the store and rebuilds the plan.
        assertTrue(store.contains(SettingsKeys.ACCESSIBILITY_TRIED))
        val steps = plan(store)

        // 3. O5 and O5b are both still there, in that order, and O5b is right after O5.
        assertTrue(StepId.ACCESSIBILITY in steps, "O5 dropped from the plan")
        assertTrue(StepId.RESTRICTED_SETTING in steps, "O5b dropped from the plan")
        assertEquals(
            steps.indexOf(StepId.ACCESSIBILITY) + 1,
            steps.indexOf(StepId.RESTRICTED_SETTING),
        )
    }

    @Test
    fun aFreshInstall_hasNoO5b() {
        // Nothing has been written: O5b is not offered before the user has seen the switch.
        val steps = plan(mutablePreferencesOf())

        assertTrue(StepId.ACCESSIBILITY in steps)
        assertFalse(StepId.RESTRICTED_SETTING in steps)
    }

    @Test
    fun grantingAccessibility_dropsO5bEvenThoughTheFlagIsStillStored() {
        val store: MutablePreferences = mutablePreferencesOf()
        PrefsMapping.writeUiPrefs(
            store,
            PrefsMapping.readUiPrefs(preferencesOf()).copy(accessibilityTried = true),
        )

        // The user turned the service on: the whole O5 branch is satisfied, so neither step remains.
        val steps = plan(store, snapshot(accessibilityEnabled = true))

        assertFalse(StepId.ACCESSIBILITY in steps)
        assertFalse(StepId.RESTRICTED_SETTING in steps)
        assertTrue(PrefsMapping.readUiPrefs(store).accessibilityTried, "the flag is a record, not a claim")
    }

    @Test
    fun backingOutOfO5b_survivesTheKill() {
        // Back (and Skip) on O5b write this, so the next return does not push the step at the user
        // again. It has to be on disk: the return from Settings is a different process.
        val store: MutablePreferences = mutablePreferencesOf()
        PrefsMapping.writeUiPrefs(
            store,
            PrefsMapping.readUiPrefs(preferencesOf())
                .copy(accessibilityTried = true, restrictedDismissed = true),
        )

        val reopened = PrefsMapping.readUiPrefs(store)

        assertTrue(reopened.restrictedDismissed)
        // Dismissal never removes the step from the plan; it only stops it being offered first, so
        // O5's own link still has somewhere to go.
        assertTrue(StepId.RESTRICTED_SETTING in plan(store))
    }

    @Test
    fun anInstallFromBeforeTheseKeys_readsThemAsOff() {
        // DataStore is key/value, not a serialised record: an old store simply has no row, and the
        // missing-key default is the same value a fresh install starts with. No migration needed.
        val oldStore: MutablePreferences = mutablePreferencesOf().apply {
            this[SettingsKeys.ONBOARDING_STEP] = 3
            this[SettingsKeys.ONBOARDING_DONE] = false
        }

        val prefs = PrefsMapping.readUiPrefs(oldStore)

        assertFalse(prefs.accessibilityTried)
        assertFalse(prefs.restrictedDismissed)
        assertFalse(StepId.RESTRICTED_SETTING in plan(oldStore))
    }

    /** B-1 must keep holding: an unreadable pop-ups answer still shows the step. */
    @Test
    fun b1UnknownPopups_stillShowsTheStep() {
        val store: MutablePreferences = mutablePreferencesOf()

        assertTrue(StepId.POPUPS in plan(store, snapshot(backgroundPopups = Tri.UNKNOWN)))
        assertFalse(StepId.POPUPS in plan(store, snapshot(backgroundPopups = Tri.YES)))
    }
}
