package com.ramish.sadiq.core.testing

import com.ramish.sadiq.core.economy.EconomyDay
import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.economy.LeaveDecision
import com.ramish.sadiq.core.economy.LeaveKind
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarEconomy
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.Kindle
import com.ramish.sadiq.core.engine.LedgerEntry
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind

/**
 * A [StarEconomy] that answers with whatever the test set, and records what the engine asked.
 *
 * Every method returns canned values, so an engine test can isolate one rule at a time. The counters
 * in [EconomyDay] are never changed except by [rollover], which only stamps the day: the real counter
 * arithmetic belongs to `DefaultStarEconomy` and is tested by WP-05, not smuggled in here.
 *
 * @property leaveDecision what a leave earns.
 * @property leaveEarnsStar what [peekLeave] answers.
 * @property kindleAward the star a confirmed kindle lights, or null when a cap drops it.
 * @property focusAwards the stars a focus session lights.
 * @property protectedAward the star a quiet window lights, or null when the window was not quiet.
 * @property freeScrollMs today's free-scroll balance.
 * @property quickNeeds quick minutes left.
 * @property projectedStars the answer to [projectFocusStars].
 */
class FakeStarEconomy(
    var leaveDecision: LeaveDecision = LeaveDecision.Kindle(EconomyRules.KINDLE_CONFIRM_MS),
    var leaveEarnsStar: Boolean = true,
    var kindleAward: StarAward? = null,
    var focusAwards: List<StarAward> = emptyList(),
    var protectedAward: StarAward? = null,
    var freeScrollMs: Long = 0L,
    var quickNeeds: Int = EngineRules.QUICK_NEEDS_PER_DAY,
    var projectedStars: Int = 0
) : StarEconomy {

    /** Method names, in call order, for interaction assertions. */
    val calls: MutableList<String> = mutableListOf()

    /** The last leave the engine reported. */
    var lastLeave: LeaveKind? = null

    /** The window the last leave happened in. */
    var lastLeaveWindow: WindowKind? = null

    /** The wall time of the last leave. */
    var lastLeaveWall: Long? = null

    /** How many kindles were pending at the last leave. */
    var lastPendingKindles: Int? = null

    /** The grant the last leave closed, if any. */
    var lastLeaveGrant: Grant? = null

    /** The last kindle confirmed. */
    var lastKindle: Kindle? = null

    /** The last focus session accounted. */
    var lastFocus: FocusState? = null

    /** Whether the last focus session was completed. */
    var lastFocusCompleted: Boolean? = null

    /** The last protected window accounted. */
    var lastSpan: ProtectedSpan? = null

    /** The ledger entry the last protected window was judged against. */
    var lastLedgerEntry: LedgerEntry? = null

    override fun rollover(day: EconomyDay, logicalDay: Int): EconomyDay {
        calls += "rollover"
        return day.copy(logicalDay = logicalDay)
    }

    override fun peekLeave(
        day: EconomyDay,
        window: WindowKind,
        nowWall: Long,
        pendingKindles: Int
    ): Boolean {
        calls += "peekLeave"
        lastLeaveWindow = window
        lastPendingKindles = pendingKindles
        return leaveEarnsStar
    }

    override fun onLeave(
        day: EconomyDay,
        leave: LeaveKind,
        window: WindowKind,
        nowWall: Long,
        pendingKindles: Int,
        grant: Grant?
    ): Pair<EconomyDay, LeaveDecision> {
        calls += "onLeave"
        lastLeave = leave
        lastLeaveWindow = window
        lastLeaveWall = nowWall
        lastPendingKindles = pendingKindles
        lastLeaveGrant = grant
        return day to leaveDecision
    }

    override fun confirmKindle(
        day: EconomyDay,
        kindle: Kindle,
        nowWall: Long,
        logicalDay: Int
    ): Pair<EconomyDay, StarAward?> {
        calls += "confirmKindle"
        lastKindle = kindle
        return day to kindleAward
    }

    override fun onFocusEnded(
        day: EconomyDay,
        focus: FocusState,
        completed: Boolean,
        nowWall: Long,
        logicalDay: Int
    ): Pair<EconomyDay, List<StarAward>> {
        calls += "onFocusEnded"
        lastFocus = focus
        lastFocusCompleted = completed
        return day to focusAwards
    }

    override fun onProtectedWindowEnded(
        day: EconomyDay,
        span: ProtectedSpan,
        entry: LedgerEntry?,
        nowWall: Long,
        logicalDay: Int
    ): Pair<EconomyDay, StarAward?> {
        calls += "onProtectedWindowEnded"
        lastSpan = span
        lastLedgerEntry = entry
        return day to protectedAward
    }

    override fun freeScrollAvailableMs(day: EconomyDay): Long {
        calls += "freeScrollAvailableMs"
        return freeScrollMs
    }

    override fun quickNeedsLeft(day: EconomyDay): Int {
        calls += "quickNeedsLeft"
        return quickNeeds
    }

    override fun projectFocusStars(plannedMin: Int, alreadyLitFocusToday: Int): Int {
        calls += "projectFocusStars"
        return projectedStars
    }
}
