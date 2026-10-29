package com.ramish.sadiq.ui.settings

import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.Tri
import com.ramish.sadiq.contract.UiPrefs

/** Which permission (or OS setting) a health row reports on. */
enum class HealthRowId {
    ACCESSIBILITY,
    BACKGROUND_POPUPS,
    BATTERY,
    AUTOSTART,
    RECENTS_LOCK,
    NOTIFICATIONS,
    USAGE_ACCESS,
    LIVE_UPDATES
}

/**
 * What the row actually found.
 *
 * [ON] is a measured grant. [CONFIRMED] is something the user told Sadiq they set, on a screen
 * Android will not report on; it is green because a person said so, never because the app assumed.
 * [NOT_CHECKED] is the one that matters for trust: the reading was unavailable, so the row says so
 * instead of borrowing the last good value.
 */
enum class HealthState {
    ON,
    CONFIRMED,
    OFF,
    NOT_RUNNING,
    NOT_CHECKED,
    NOT_AVAILABLE
}

/** The three colours a row may use. [UNKNOWN] is deliberately not red: an unanswered check is not a failure. */
enum class HealthTone {
    OK,
    NEEDS_ACTION,
    UNKNOWN
}

/**
 * One line of the Health check.
 *
 * The row carries facts, not sentences: the screen owns the copy so the same state reads the same way
 * everywhere and the logic stays testable without Android resources.
 *
 * @property id which check this is.
 * @property state what was found.
 * @property tone how it should read.
 * @property target the OS page that fixes it, or null when there is nothing to open (a row that is
 *   already on, or the Recents gesture, which has no settings page of its own).
 */
data class HealthRow(
    val id: HealthRowId,
    val state: HealthState,
    val tone: HealthTone,
    val target: SettingsTarget?
)

/**
 * Turns one permission reading into the Health check's rows.
 *
 * Pure on purpose: no Android types, no clock, no strings. It is the single place where a permission
 * becomes a claim, so "no green tick without evidence" is proved here once rather than trusted to
 * eight composables.
 *
 * Rows that do not exist on this phone are left out rather than shown as unknown: a Pixel has no
 * HyperOS pop-up op, and listing one would invent a problem. [Live updates][HealthRowId.LIVE_UPDATES]
 * is the exception — it is an Android 16 feature, and on an older phone the row says so.
 */
object HealthRows {

    /**
     * The rows for one snapshot and one set of preferences.
     *
     * @param snapshot what `PermissionStatusProvider` last read.
     * @param prefs the self-reported HyperOS steps from onboarding.
     * @return the rows in the order SPEC-UX S6 lists them.
     */
    fun from(snapshot: PermissionSnapshot, prefs: UiPrefs): List<HealthRow> = buildList {
        add(accessibility(snapshot))
        if (snapshot.isXiaomiFamily) {
            add(backgroundPopups(snapshot))
            add(battery(snapshot, prefs))
            add(autostart(snapshot, prefs))
            add(recents(prefs))
        } else {
            add(battery(snapshot, prefs))
        }
        add(notifications(snapshot))
        add(usageAccess(snapshot))
        add(liveUpdates(snapshot))
    }

    /**
     * Accessibility has three readings, not two: the switch can be on while the service is dead,
     * which is the state HyperOS leaves behind after it kills the process.
     */
    private fun accessibility(snapshot: PermissionSnapshot): HealthRow = when {
        snapshot.accessibilityEnabled && snapshot.accessibilityConnected ->
            HealthRow(HealthRowId.ACCESSIBILITY, HealthState.ON, HealthTone.OK, null)

        snapshot.accessibilityEnabled ->
            HealthRow(
                HealthRowId.ACCESSIBILITY,
                HealthState.NOT_RUNNING,
                HealthTone.NEEDS_ACTION,
                SettingsTarget.ACCESSIBILITY_DETAILS
            )

        else ->
            HealthRow(HealthRowId.ACCESSIBILITY, HealthState.OFF, HealthTone.NEEDS_ACTION, SettingsTarget.ACCESSIBILITY)
    }

    private fun backgroundPopups(snapshot: PermissionSnapshot): HealthRow = when (snapshot.backgroundPopups) {
        Tri.YES -> HealthRow(HealthRowId.BACKGROUND_POPUPS, HealthState.ON, HealthTone.OK, null)
        Tri.NO -> HealthRow(
            HealthRowId.BACKGROUND_POPUPS,
            HealthState.OFF,
            HealthTone.NEEDS_ACTION,
            SettingsTarget.MIUI_OTHER_PERMISSIONS
        )

        Tri.UNKNOWN -> HealthRow(
            HealthRowId.BACKGROUND_POPUPS,
            HealthState.NOT_CHECKED,
            HealthTone.UNKNOWN,
            SettingsTarget.MIUI_OTHER_PERMISSIONS
        )
    }

    /**
     * Battery needs two facts on HyperOS: the AOSP exemption (readable) and the OEM "No restrictions"
     * setting (not readable). One green tick cannot cover both, so a phone with only the exemption
     * reads "Not checked" until the user confirms the second half.
     */
    private fun battery(snapshot: PermissionSnapshot, prefs: UiPrefs): HealthRow = when {
        !snapshot.batteryUnrestricted ->
            HealthRow(HealthRowId.BATTERY, HealthState.OFF, HealthTone.NEEDS_ACTION, SettingsTarget.BATTERY_OPT_REQUEST)

        !snapshot.isXiaomiFamily ->
            HealthRow(HealthRowId.BATTERY, HealthState.ON, HealthTone.OK, null)

        prefs.batterySaverConfirmed ->
            HealthRow(HealthRowId.BATTERY, HealthState.CONFIRMED, HealthTone.OK, null)

        else ->
            HealthRow(HealthRowId.BATTERY, HealthState.NOT_CHECKED, HealthTone.UNKNOWN, SettingsTarget.MIUI_BATTERY_SAVER)
    }

    private fun autostart(snapshot: PermissionSnapshot, prefs: UiPrefs): HealthRow = when {
        snapshot.autostart == Tri.YES ->
            HealthRow(HealthRowId.AUTOSTART, HealthState.ON, HealthTone.OK, null)

        // A readable "no" is the truth even when the user believes they set it.
        snapshot.autostart == Tri.NO ->
            HealthRow(HealthRowId.AUTOSTART, HealthState.OFF, HealthTone.NEEDS_ACTION, SettingsTarget.MIUI_AUTOSTART)

        prefs.autostartConfirmed ->
            HealthRow(HealthRowId.AUTOSTART, HealthState.CONFIRMED, HealthTone.OK, null)

        else ->
            HealthRow(HealthRowId.AUTOSTART, HealthState.NOT_CHECKED, HealthTone.UNKNOWN, SettingsTarget.MIUI_AUTOSTART)
    }

    /** There is no settings page for the Recents gesture, so this row never grows a link. */
    private fun recents(prefs: UiPrefs): HealthRow = if (prefs.recentsLockedConfirmed) {
        HealthRow(HealthRowId.RECENTS_LOCK, HealthState.CONFIRMED, HealthTone.OK, null)
    } else {
        HealthRow(HealthRowId.RECENTS_LOCK, HealthState.NOT_CHECKED, HealthTone.UNKNOWN, null)
    }

    private fun notifications(snapshot: PermissionSnapshot): HealthRow = if (snapshot.notifications) {
        HealthRow(HealthRowId.NOTIFICATIONS, HealthState.ON, HealthTone.OK, null)
    } else {
        HealthRow(HealthRowId.NOTIFICATIONS, HealthState.OFF, HealthTone.NEEDS_ACTION, SettingsTarget.NOTIFICATIONS)
    }

    private fun usageAccess(snapshot: PermissionSnapshot): HealthRow = if (snapshot.usageAccess) {
        HealthRow(HealthRowId.USAGE_ACCESS, HealthState.ON, HealthTone.OK, null)
    } else {
        HealthRow(HealthRowId.USAGE_ACCESS, HealthState.OFF, HealthTone.NEEDS_ACTION, SettingsTarget.USAGE_ACCESS)
    }

    /**
     * Promoted ongoing notifications only exist from API 36. Below that the honest answer is that the
     * phone does not offer the feature, not that the user has not turned it on.
     */
    private fun liveUpdates(snapshot: PermissionSnapshot): HealthRow {
        if (snapshot.sdkInt < PROMOTED_NOTIFICATIONS_SDK) {
            return HealthRow(HealthRowId.LIVE_UPDATES, HealthState.NOT_AVAILABLE, HealthTone.UNKNOWN, null)
        }
        return when (snapshot.liveUpdates) {
            Tri.YES -> HealthRow(HealthRowId.LIVE_UPDATES, HealthState.ON, HealthTone.OK, null)
            Tri.NO -> HealthRow(HealthRowId.LIVE_UPDATES, HealthState.OFF, HealthTone.NEEDS_ACTION, SettingsTarget.LIVE_UPDATES)
            Tri.UNKNOWN -> HealthRow(
                HealthRowId.LIVE_UPDATES,
                HealthState.NOT_CHECKED,
                HealthTone.UNKNOWN,
                SettingsTarget.LIVE_UPDATES
            )
        }
    }

    /** `canPostPromotedNotifications` landed in Android 16 (API 36). */
    private const val PROMOTED_NOTIFICATIONS_SDK: Int = 36
}
