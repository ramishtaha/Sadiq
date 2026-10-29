package com.ramish.sadiq.core.economy

import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.Kindle
import com.ramish.sadiq.core.engine.LedgerEntry
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind

/**
 * The star economy: every earning rule, cap and anti-gaming check of §3.8, as pure functions.
 *
 * The implementation holds no state. The counters live in [EconomyDay], which the engine persists, so
 * a restart cannot award the same star twice or forget a cap.
 */
interface StarEconomy {
    /**
     * Move the counters to [logicalDay], resetting the daily ones. Stars already lit are untouched.
     *
     * @param day the counters before 04:00.
     * @param logicalDay the logical day now in force.
     */
    fun rollover(day: EconomyDay, logicalDay: Int): EconomyDay

    /**
     * Whether a leave from [window] would earn a star right now, without recording anything.
     *
     * Used to decide whether the surface shows the star beside "No, take me home", so the button
     * never promises a star the economy will refuse (§3.8: "Leave" versus "Leave · light a star").
     *
     * @param day today's counters.
     * @param window the window the leave happens in.
     * @param nowWall wall time of the leave.
     * @param pendingKindles how many kindles are already waiting.
     */
    fun peekLeave(day: EconomyDay, window: WindowKind, nowWall: Long, pendingKindles: Int): Boolean

    /**
     * Record a leave.
     *
     * @param day today's counters.
     * @param leave how the user left.
     * @param window the window the leave happened in.
     * @param nowWall wall time of the leave.
     * @param pendingKindles how many kindles are already waiting.
     * @param grant the grant being closed, when the leave closes one.
     * @return the updated counters and what the leave earned.
     */
    fun onLeave(
        day: EconomyDay,
        leave: LeaveKind,
        window: WindowKind,
        nowWall: Long,
        pendingKindles: Int,
        grant: Grant?
    ): Pair<EconomyDay, LeaveDecision>

    /**
     * Light a kindle whose confirmation time has passed.
     *
     * @param day today's counters.
     * @param kindle the kindle that came due.
     * @param nowWall wall time of the confirmation.
     * @param logicalDay the logical day it lights on.
     * @return the updated counters and the star, or null when a cap drops it silently.
     */
    fun confirmKindle(
        day: EconomyDay,
        kindle: Kindle,
        nowWall: Long,
        logicalDay: Int
    ): Pair<EconomyDay, StarAward?>

    /**
     * Award the focus stars when a session ends.
     *
     * @param day today's counters.
     * @param focus the session as it stands.
     * @param completed whether the planned time was reached.
     * @param nowWall wall time it ended.
     * @param logicalDay the logical day it ended on.
     * @return the updated counters and the stars that lit, in order.
     */
    fun onFocusEnded(
        day: EconomyDay,
        focus: FocusState,
        completed: Boolean,
        nowWall: Long,
        logicalDay: Int
    ): Pair<EconomyDay, List<StarAward>>

    /**
     * Award a quiet-night or quiet-morning star when its window ends.
     *
     * @param day today's counters.
     * @param span the window that ended.
     * @param entry what the ledger recorded for it, or null when nothing was recorded.
     * @param nowWall wall time the window ended.
     * @param logicalDay the logical day it ended on.
     * @return the updated counters and the star, or null when the window was not quiet.
     */
    fun onProtectedWindowEnded(
        day: EconomyDay,
        span: ProtectedSpan,
        entry: LedgerEntry?,
        nowWall: Long,
        logicalDay: Int
    ): Pair<EconomyDay, StarAward?>

    /**
     * Today's free-scroll balance: [EconomyDay.litTotal] minutes minus what has been used, capped at
     * [EconomyRules.FREE_SCROLL_DAILY_MAX_MS].
     *
     * @param day today's counters.
     */
    fun freeScrollAvailableMs(day: EconomyDay): Long

    /**
     * Quick minutes left today.
     *
     * @param day today's counters.
     */
    fun quickNeedsLeft(day: EconomyDay): Int

    /**
     * The stars a focus session of [plannedMin] would earn, for the Focus screen's promise before it
     * starts. Uses the same formula as [onFocusEnded] (§3.8).
     *
     * @param plannedMin the length the user is considering.
     * @param alreadyLitFocusToday focus stars already lit today, against the daily cap.
     */
    fun projectFocusStars(plannedMin: Int, alreadyLitFocusToday: Int): Int
}
