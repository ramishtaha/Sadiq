package com.ramish.sadiq.core.strict

import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.model.SurfaceMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One settings edit, as a value.
 *
 * The data layer persists pending changes as JSON, so this is the one user action that survives a
 * reboot in flight. The wire names are explicit and must not be renamed: a pending change written by
 * an older build has to decode after an update.
 */
@Serializable
sealed interface SettingsChange {
    /** Change the mode. @property mode the new mode. */
    @Serializable
    @SerialName("set_mode")
    data class SetMode(val mode: Mode) : SettingsChange

    /**
     * The guarded app list.
     *
     * @property apps the complete selection, as the screen that made it built it.
     * @property removed the packages this edit drops, recorded by the data layer only when the edit
     *   is **queued** for "Think it over". `null` — the default, and every live edit — means "write
     *   [apps] as it stands". A queued row is rebased onto the list in force when it lands, so a
     *   snapshot that waited can never un-guard an app added while it waited (AUDIT-4 A4-12).
     *
     *   Nullable rather than defaulting to an empty set, because an empty set is a claim — "this
     *   edit removes nothing" — and a row written by a build that did not record removals cannot
     *   make that claim. See `rebasedOnto` in `PendingChangeRebase.kt`.
     */
    @Serializable
    @SerialName("set_apps")
    data class SetApps(
        val apps: List<GuardedApp>,
        val removed: Set<String>? = null
    ) : SettingsChange

    /**
     * Change one app's surface mode.
     *
     * @property packageName the app.
     * @property mode whether the whole app or only its short-video surface is guarded.
     */
    @Serializable
    @SerialName("set_surface_mode")
    data class SetSurfaceMode(val packageName: String, val mode: SurfaceMode) : SettingsChange

    /** Replace the guarded domains. @property domains the complete new set. */
    @Serializable
    @SerialName("set_domains")
    data class SetDomains(val domains: Set<String>) : SettingsChange

    /** Change the bedtime gate. @property cfg the new gate settings. */
    @Serializable
    @SerialName("set_sleep_gate")
    data class SetSleepGate(val cfg: SleepGateConfig) : SettingsChange

    /** Change the morning window. @property cfg the new morning settings. */
    @Serializable
    @SerialName("set_morning")
    data class SetMorning(val cfg: MorningConfig) : SettingsChange

    /** Change the prayer inputs. @property cfg the new prayer settings. */
    @Serializable
    @SerialName("set_prayer")
    data class SetPrayer(val cfg: PrayerConfig) : SettingsChange

    /** Change Ramadan behaviour. @property cfg the new Ramadan settings. */
    @Serializable
    @SerialName("set_ramadan")
    data class SetRamadan(val cfg: RamadanConfig) : SettingsChange

    /** Change the delay on loosening changes. @property cfg the new strict settings. */
    @Serializable
    @SerialName("set_strict")
    data class SetStrict(val cfg: StrictConfig) : SettingsChange

    /**
     * Change the three switches that are neither times nor lists.
     *
     * A null field means "leave this one alone", so two screens can edit different flags without
     * overwriting each other.
     *
     * @property reflection whether check-ins are on.
     * @property gymHint whether training hints are on.
     * @property webGuard whether guarded domains are enforced.
     */
    @Serializable
    @SerialName("set_flags")
    data class SetFlags(
        val reflection: Boolean?,
        val gymHint: Boolean?,
        val webGuard: Boolean?
    ) : SettingsChange
}

/** Whether a change makes the app stricter, looser, or neither. */
enum class ChangeClass {
    TIGHTENING,
    LOOSENING,
    NEUTRAL
}

/**
 * "Think it over": classifies a change and decides when it may take effect.
 *
 * Only loosening changes wait. Tightening something never needs a delay, because the user is asking
 * for less of what he is trying to avoid.
 */
interface StrictPolicy {
    /**
     * @param current the settings in force.
     * @param change the edit being requested.
     */
    fun classify(current: GuardConfig, change: SettingsChange): ChangeClass

    /**
     * When the change may be applied.
     *
     * @param nowWall wall time of the request.
     * @param cls how the change was classified.
     * @param strict the delay settings.
     * @param ramadanActive whether Ramadan is in force, which lengthens the delay.
     * @return the wall time it takes effect; equal to [nowWall] for neutral and tightening changes.
     */
    fun applyAtWall(
        nowWall: Long,
        cls: ChangeClass,
        strict: StrictConfig,
        ramadanActive: Boolean
    ): Long
}
