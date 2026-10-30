package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.EconomyDay
import com.ramish.sadiq.core.economy.LeaveKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import kotlinx.serialization.Serializable

/** What the engine is doing, and therefore what the user sees (§3.6). */
enum class Phase {
    IDLE,
    PROMPTING,
    GRANTED,
    HELD,
    PAUSED
}

/** Which surface covers a guarded app. One value per screen in §4.4. */
enum class SurfaceKind {
    PAUSE,
    REFLECT,
    PRAYER,
    MORNING,
    COOLDOWN,
    SLEEP_GATE,
    FOCUS_GUARD,
    TIME_UP,
    TEST
}

/** What kind of time a grant holds. */
enum class GrantKind {
    STAY,
    FREE,
    QUICK,
    PRAYER_STAY,

    /**
     * "Open anyway": the ten discouraged minutes from the door on a surface that offers no yes of
     * its own. It never lights a star and never touches the sky (§A.0).
     */
    OVERRIDE
}

/** Why a grant stopped. Recorded, never inferred later. */
enum class GrantEnd {
    EXPIRED,
    DONE_EARLY,
    LEFT,
    PAUSE_EXPIRED,
    DISCARDED,
    SUPERSEDED
}

/** Which surface of a guarded app is in front. */
enum class AppSurface {
    APP,
    SHORTS,
    PIP
}

/**
 * A slice of guarded time the user chose.
 *
 * A grant is never revoked mid-use (§3.6): a window that begins during a Stay waits for the Stay to
 * finish. Consumption is tracked in both wall and elapsed time because the two answer different
 * questions — [startedWall] dates the record, [lastAccountedElapsed] measures it.
 *
 * @property id engine sequence id, e.g. `g42`; also the history key.
 * @property kind what the time was granted for.
 * @property grantedMs the length the user chose.
 * @property consumedMs how much of it has been used.
 * @property startedWall wall time the grant began.
 * @property lastAccountedElapsed elapsed reading at the last accounting step.
 * @property heldSinceElapsed when the app was left or the screen went off mid-grant, else null.
 * @property pausedSinceElapsed when the user saved the grant for later, else null.
 * @property windowAtStart the window kind when the grant began, so a return can tell whether it changed.
 * @property firstPackage the first guarded app the grant covered, for the record.
 */
@Serializable
data class Grant(
    val id: String,
    val kind: GrantKind,
    val grantedMs: Long,
    val consumedMs: Long,
    val startedWall: Long,
    val lastAccountedElapsed: Long,
    val heldSinceElapsed: Long?,
    val pausedSinceElapsed: Long?,
    val windowAtStart: WindowKind,
    val firstPackage: String
)

/**
 * Today's guarded budget.
 *
 * @property remainingMs what is left; below [EngineRules.BUDGET_EPSILON_MS] counts as exhausted.
 * @property lastGuardedUseElapsed elapsed reading of the last guarded use, the cooldown reference.
 */
@Serializable
data class Budget(
    val remainingMs: Long,
    val lastGuardedUseElapsed: Long?
)

/**
 * A rest after an exhausted budget.
 *
 * @property remainingMs rest left.
 * @property lastAccountedElapsed elapsed reading at the last accounting step; a reboot uses the wall
 *   delta instead, so a night with the phone off still ends a rest (§3.6).
 */
@Serializable
data class Rest(
    val remainingMs: Long,
    val lastAccountedElapsed: Long
)

/**
 * A running focus session.
 *
 * @property id engine sequence id, prefixed `f`.
 * @property plannedMs the length the user chose.
 * @property elapsedMs time accumulated so far.
 * @property lastAccountedElapsed elapsed reading at the last accounting step.
 * @property startedWall wall time the session began.
 * @property sound the soundscape playing with it, [SoundKind.NONE] for silence.
 */
@Serializable
data class FocusState(
    val id: String,
    val plannedMs: Long,
    val elapsedMs: Long,
    val lastAccountedElapsed: Long,
    val startedWall: Long,
    val sound: SoundKind
)

/**
 * A star waiting to light.
 *
 * A leave earns a star only if the guarded app stays closed for [EconomyRules.KINDLE_CONFIRM_MS];
 * any guarded foreground before then extinguishes the kindle silently (§3.8).
 *
 * @property id engine sequence id, prefixed `k`.
 * @property leave which leave the kindle came from, and therefore which star it becomes.
 * @property createdElapsed elapsed reading when the kindle was made.
 * @property confirmAtElapsed elapsed reading at which it lights.
 * @property sourcePackage the app that was left, for the star's tooltip.
 * @property window the window the leave happened in.
 */
@Serializable
data class Kindle(
    val id: String,
    val leave: LeaveKind,
    val createdElapsed: Long,
    val confirmAtElapsed: Long,
    val sourcePackage: String?,
    val window: WindowKind
)

/**
 * What a protected window is owed, decided before the window ends.
 *
 * The ledger is how a quiet night is proven rather than guessed: any grant or reconciled guarded use
 * inside the window disqualifies it (§3.8, E7/E8).
 *
 * @property kind the protected window kind.
 * @property startWall window start in wall milliseconds.
 * @property grants STAY, FREE or PRAYER_STAY grants opened inside it.
 * @property quickNeeds one-minute grants opened inside it.
 * @property overrides "Open anyway" grants opened inside it; a count of its own, never a star (§A.9).
 * @property reconciledUsage whether usage stats reported guarded use inside it.
 */
@Serializable
data class LedgerEntry(
    val kind: WindowKind,
    val startWall: Long,
    val grants: Int,
    val quickNeeds: Int,
    val reconciledUsage: Boolean,
    val overrides: Int = 0
)

/**
 * The question currently on screen.
 *
 * @property kind which surface is showing.
 * @property packageName the guarded app underneath it.
 * @property domain the matched guarded domain, when a browser is the target.
 * @property shownWall wall time the surface appeared, used for the decision-time record.
 * @property reflectReason the reason chosen in a check-in, once one has been chosen.
 * @property testMode whether this is the onboarding or Health-check self-test.
 * @property returnRoute where a self-test should send the user afterwards.
 * @property overrideArmedElapsed elapsed reading when "Open anyway" was armed; non-null means the
 *   confirm view is showing (§A.2).
 */
@Serializable
data class Prompt(
    val kind: SurfaceKind,
    val packageName: String,
    val domain: String?,
    val shownWall: Long,
    val reflectReason: ReflectionReason?,
    val testMode: Boolean,
    val returnRoute: String?,
    val overrideArmedElapsed: Long? = null
)

/**
 * A self-test in flight.
 *
 * The app arms this, then launches the target app itself, so the real interception path runs. The
 * arm expires after [EngineRules.SELF_TEST_ARM_MS] so a forgotten test cannot guard an app later.
 *
 * @property packageName the app the test will open.
 * @property returnRoute where to send the user when the test surface is dismissed.
 * @property armedElapsed elapsed reading when the arm was set.
 */
@Serializable
data class TestArm(
    val packageName: String,
    val returnRoute: String,
    val armedElapsed: Long
)

/**
 * The whole persisted engine state. Saved as one JSON document (§3.4) and reloaded on process start.
 *
 * Every field is here because the engine must survive HyperOS killing the process mid-grant: a
 * restart resumes the grant, the rest and the focus instead of forgetting them.
 *
 * @property schema snapshot format version; a decode failure falls back to a fresh state.
 * @property phase what the engine is doing.
 * @property foregroundPackage the app in front, or null.
 * @property foregroundGuarded whether that app is currently a guarded target.
 * @property screenOn whether the screen is on.
 * @property prompt the surface on screen, if any.
 * @property grant the grant in force, if any. At most one exists at a time.
 * @property budget today's guarded budget.
 * @property rest the running rest, if any.
 * @property focus the running focus session, if any.
 * @property kindles pending kindles; at most one (§3.8).
 * @property recentOpensWall recent guarded opens, for the reflection trigger.
 * @property lastReflectWall when the last check-in was answered.
 * @property ledger what protected windows have been owed so far.
 * @property economy today's star and free-scroll counters.
 * @property testArm the self-test arm, if one is live.
 * @property bootCount the boot the elapsed readings belong to.
 * @property seq monotonic id counter for grants, kindles and focus sessions.
 * @property lastSeenWall wall time of the previous input, for cross-boot accounting.
 * @property lastSeenElapsed elapsed time of the previous input.
 */
@Serializable
data class EngineState(
    val schema: Int = 1,
    val phase: Phase,
    val foregroundPackage: String?,
    val foregroundGuarded: Boolean,
    val screenOn: Boolean,
    val prompt: Prompt?,
    val grant: Grant?,
    val budget: Budget,
    val rest: Rest?,
    val focus: FocusState?,
    val kindles: List<Kindle>,
    val recentOpensWall: List<Long>,
    val lastReflectWall: Long?,
    val ledger: List<LedgerEntry>,
    val economy: EconomyDay,
    val testArm: TestArm?,
    val bootCount: Int,
    val seq: Long,
    val lastSeenWall: Long,
    val lastSeenElapsed: Long
)
