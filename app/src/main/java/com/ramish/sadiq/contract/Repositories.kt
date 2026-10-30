package com.ramish.sadiq.contract

import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.insight.WeeklyInput
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.strict.SettingsChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How the app picks a palette. [SYSTEM] follows the OS setting; the other three are explicit. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA
}

/**
 * The user's non-guard preferences, stored in the `settings` DataStore (§3.4).
 *
 * Every field has a documented default on the DataStore side; this value is always complete, so a
 * screen never has to ask whether a preference has been written yet.
 *
 * @property themeMode the palette choice.
 * @property reduceMotion whether animations are shortened or removed.
 * @property onboardingDone whether the wizard has been finished.
 * @property onboardingStep the last step index reached, so a killed wizard resumes.
 * @property keepAlive whether the foreground service runs to keep timers alive.
 * @property weeklyNotif whether the weekly review notification is wanted.
 * @property focusDefaultMin the pre-selected focus length.
 * @property focusDefaultSound the pre-selected focus soundscape.
 * @property recentsLockedConfirmed self-reported HyperOS "lock in Recents" step.
 * @property autostartConfirmed self-reported HyperOS autostart step.
 * @property batterySaverConfirmed self-reported "No restrictions" step.
 * @property skySeed the install's star-layout seed, written once on first launch.
 * @property welcomeStarGiven whether the onboarding self-test star has been awarded.
 * @property accessibilityTried whether the user has been sent to the accessibility page once. O5b
 *   exists only after that page has been seen. It is a preference and not a wizard field because
 *   HyperOS kills the process while the user is in Settings: an in-memory flag would vanish exactly
 *   when O5b is the step they need.
 * @property restrictedDismissed whether the user has answered O5b, so it stops being offered
 *   automatically. Kept beside the flag above for the same reason.
 */
data class UiPrefs(
    val themeMode: ThemeMode,
    val reduceMotion: Boolean,
    val onboardingDone: Boolean,
    val onboardingStep: Int,
    val keepAlive: Boolean,
    val weeklyNotif: Boolean,
    val focusDefaultMin: Int,
    val focusDefaultSound: SoundKind,
    val recentsLockedConfirmed: Boolean,
    val autostartConfirmed: Boolean,
    val batterySaverConfirmed: Boolean,
    val skySeed: Long,
    val welcomeStarGiven: Boolean,
    val accessibilityTried: Boolean = false,
    val restrictedDismissed: Boolean = false
)

/**
 * The theme and preference seam.
 *
 * Bound optionally in M1: until the data layer exists, [MainActivity][com.ramish.sadiq.MainActivity]
 * injects `Optional<UiPrefsSource>` and falls back to the system theme. That is what lets the nav
 * shell run before WP-09 lands without anyone editing the activity later.
 */
interface UiPrefsSource {
    /** The user's preferences. Emits immediately and on every change. */
    val prefs: Flow<UiPrefs>
}

/** What happened to a settings change. */
sealed interface ApplyResult {
    /** The change is in force now. */
    data object Applied : ApplyResult

    /**
     * The change is stored and waits.
     *
     * @property applyAtWall the wall time it takes effect.
     */
    data class Scheduled(val applyAtWall: Long) : ApplyResult
}

/**
 * The guarded settings, plus the preferences.
 *
 * [apply] is the only mutation path, so "Think it over" can intercept every loosening change in one
 * place instead of in each screen.
 */
interface SettingsRepository : UiPrefsSource {
    /** The settings in force. Hot: the engine reads it synchronously. */
    val config: StateFlow<GuardConfig>

    /**
     * Apply one edit.
     *
     * @param change what to change.
     * @return whether it took effect now or waits for `ChangeGate`.
     */
    suspend fun apply(change: SettingsChange): ApplyResult

    /**
     * Read-modify-write the preferences.
     *
     * @param transform the change, applied to the current value.
     */
    suspend fun updatePrefs(transform: (UiPrefs) -> UiPrefs)

    /**
     * Count an overlay fallback for the day, for the "Allow pop-ups for faster pauses" card.
     *
     * @param logicalDay the logical day to count against.
     * @return today's count after the increment.
     */
    suspend fun bumpOverlayFallback(logicalDay: Int): Int
}

/** The sky: permanently lit stars, appended and never updated. */
interface StarRepository {
    /** Every lit star, oldest first. */
    fun observeSky(): Flow<List<SkyStar>>

    /** Append one star. @param award the star to record. */
    suspend fun insert(award: StarAward)

    /** How many stars the sky holds. */
    suspend fun countLit(): Int

    /** Delete every star and every history row, for "Delete all history". */
    suspend fun deleteAll()
}

/** Everything Sadiq remembers about what happened, for the Week screen and the ledger. */
interface HistoryRepository {
    /** Write one fact. @param event the fact to record. */
    suspend fun record(event: RecordEvent)

    /**
     * The material for a weekly review.
     *
     * @param days the days under review.
     * @param prevDays the comparison week.
     * @param config the settings in force, so the builder can suggest a change to them.
     */
    suspend fun weekInput(days: IntRange, prevDays: IntRange, config: GuardConfig): WeeklyInput

    /** Merge usage rows, replacing any existing row for the same day and package. */
    suspend fun upsertUsageDays(rows: List<UsageDayRow>)

    /** Delete the five history tables and nothing else. */
    suspend fun deleteAll()
}

/**
 * A settings change waiting for its time.
 *
 * @property id the row id.
 * @property createdAt when it was requested.
 * @property applyAt when it takes effect.
 * @property change the stored edit, serialised as JSON in `pending_changes`.
 */
data class PendingChange(
    val id: Long,
    val createdAt: Long,
    val applyAt: Long,
    val change: SettingsChange
)

/** The queue of delayed settings changes ("Think it over"). M2. */
interface PendingChangesRepository {
    /** Every pending change, earliest first. */
    fun observe(): Flow<List<PendingChange>>

    /**
     * Store a change.
     *
     * @param change the edit.
     * @param applyAt the wall time it takes effect.
     */
    suspend fun add(change: SettingsChange, applyAt: Long)

    /** Changes whose time has come. @param nowWall the current wall time. */
    suspend fun due(nowWall: Long): List<PendingChange>

    /** Drop a change, applied or cancelled. @param id the row id. */
    suspend fun remove(id: Long)
}

/**
 * The engine snapshot, stored as one JSON document (`engine_state.json`, §3.4).
 *
 * A decode failure returns null and the controller starts fresh; it never throws and never blocks
 * interception.
 */
interface EngineSnapshotStore {
    /** The saved state, or null when there is none or it cannot be read. */
    suspend fun load(): EngineState?

    /** Save the state. @param state the state to persist. */
    suspend fun save(state: EngineState)
}

/** What the gate decided about a change. */
sealed interface GateDecision {
    /** Apply it now. */
    data object ApplyNow : GateDecision

    /**
     * Apply it later.
     *
     * @property applyAtWall the wall time it takes effect.
     */
    data class Defer(val applyAtWall: Long) : GateDecision
}

/**
 * "Think it over": the optional seam that delays loosening changes.
 *
 * Absent in M1 (bound with `@BindsOptionalOf`), so the data layer applies changes immediately until
 * the strict-mode feature exists. Nothing else in the app knows whether it is present.
 */
interface ChangeGate {
    /**
     * @param current the settings in force.
     * @param change the edit being requested.
     * @param nowWall the current wall time.
     */
    suspend fun gate(current: GuardConfig, change: SettingsChange, nowWall: Long): GateDecision
}
