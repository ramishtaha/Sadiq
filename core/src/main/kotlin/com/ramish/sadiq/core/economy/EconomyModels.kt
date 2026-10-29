package com.ramish.sadiq.core.economy

import com.ramish.sadiq.core.model.WindowKind
import kotlinx.serialization.Serializable

/** What a star records. One value per line of the tooltip table (§1.5 of the name addendum). */
enum class StarKind {
    LEAVE,
    KEPT_WORD,
    DONE_EARLY,
    FOCUS,
    FOCUS_FINISH,
    QUIET_NIGHT,
    PROTECTED_MORNING,
    WELCOME
}

/** How the user left. Decides which star a kindle becomes, and whether one is earned at all. */
enum class LeaveKind {
    LEFT_PROMPT,
    LEFT_GESTURE,
    SCREEN_OFF,
    KEPT_WORD,
    DONE_EARLY,
    WELCOME
}

/** Why a leave did not earn a star. Recorded so the surface can be honest without a second guess. */
enum class NoStarReason {
    RATE_LIMIT,
    LEAVE_CAP,
    DAILY_CAP,
    SLEEP_GATE,
    PENDING,
    NOT_ELIGIBLE
}

/**
 * Today's star and free-scroll counters. Persisted inside the engine state, so the day's balance
 * survives a process death.
 *
 * The counters reset at 04:00 with the logical day; the stars themselves never do (§3.8).
 *
 * @property logicalDay the logical day these counters belong to.
 * @property litLeave leave-type stars lit today (E1–E3), against [EconomyRules.LEAVE_DAILY_CAP].
 * @property litFocus focus stars lit today (E4–E6), against [EconomyRules.FOCUS_DAILY_CAP].
 * @property litTotal every star lit today except WELCOME, against [EconomyRules.DAILY_CAP].
 * @property lastLeaveLitWall wall time of the last lit leave-type star, the rate-limit reference.
 * @property freeUsedMs free scroll already spent today; the balance is litTotal minutes minus this.
 * @property quickNeedsUsed quick minutes already taken today.
 */
@Serializable
data class EconomyDay(
    val logicalDay: Int,
    val litLeave: Int = 0,
    val litFocus: Int = 0,
    val litTotal: Int = 0,
    val lastLeaveLitWall: Long? = null,
    val freeUsedMs: Long = 0,
    val quickNeedsUsed: Int = 0
)

/**
 * A star that lit, and everything needed to draw it and explain it later.
 *
 * Stars are never spent, removed or expired; they are the permanent record of a choice (§3.8).
 *
 * @property kind what the star records.
 * @property litWall wall time it lit.
 * @property logicalDay the logical day it belongs to.
 * @property window the window it was earned in, or [WindowKind.NONE].
 * @property sourcePackage the app left or the app the focus ran over, when relevant.
 * @property sourceRef the grant or focus id it closes, when relevant.
 */
@Serializable
data class StarAward(
    val kind: StarKind,
    val litWall: Long,
    val logicalDay: Int,
    val window: WindowKind,
    val sourcePackage: String?,
    val sourceRef: String?
)

/** What a leave earns: a kindle to confirm, a star right now, or nothing with a reason. */
sealed interface LeaveDecision {
    /**
     * A star is pending. It lights if no guarded app comes to the front for
     * [EconomyRules.KINDLE_CONFIRM_MS].
     *
     * @property confirmAfterMs how long the proof lasts.
     */
    data class Kindle(val confirmAfterMs: Long) : LeaveDecision

    /** The star lights immediately: "I'm done" after a Stay is its own proof (E3). */
    data class LitNow(val award: StarAward) : LeaveDecision

    /** No star, and why. The surface still says "Good choice." (§3.8). */
    data class None(val reason: NoStarReason) : LeaveDecision
}
