package com.ramish.sadiq.core.economy

import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.Kindle
import com.ramish.sadiq.core.engine.LedgerEntry
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.WindowKind

// CONTRACT-GAP 1/3 — E7 pays 3 stars and E8 pays 2, but `onProtectedWindowEnded` returns a single
// `StarAward?`. The interface method therefore books every star into EconomyDay and returns the first;
// [DefaultStarEconomy.onProtectedWindowEndedAwards] hands the caller one award per star so the sky can
// show all of them. WP-06 currently records one StarLit per window, so a quiet night counts as three
// stars in EconomyDay and draws one.
// CONTRACT-GAP 2/3 — StarEconomy has no `onFreeScroll`/`onQuickNeed` method, so this class cannot debit
// `freeUsedMs`/`quickNeedsUsed`; WP-06 writes both fields directly when a FREE/QUICK grant is granted.
// The two readers below are written against that split.
// CONTRACT-GAP 3/3 — WindowKind has no FOCUS_GUARD or COOLDOWN member and `peekLeave`/`onLeave` take no
// grant on the peek path, so two of E1's exclusions can only be enforced by the caller: "not from
// FOCUS_GUARD" and "not out of a FREE grant" when the UI asks `peekLeave` what the button should say.
// This file does refuse leaves in SLEEP_GATE and leaves whose grant is FREE or QUICK.

/**
 * The one conversion the economy needs and [EconomyRules] does not publish, because every rule there is
 * already expressed in milliseconds.
 */
private const val MINUTE_MS: Long = 60_000L

/**
 * The single implementation of [StarEconomy] (§3.8): earning rules E1–E9, every cap, the leave rate
 * limit and the free-scroll balance, as pure functions.
 *
 * The economy keeps no state and reads no clock. Every call takes the current [EconomyDay] and returns
 * a new one, so the engine can hold the day inside `EngineState` and replay it deterministically.
 * Timestamps are only ever used to *stamp* awards and to measure the leave rate limit; durations
 * (kindle confirmation, focus accounting) are measured in `elapsedRealtime` by the caller, which is why
 * nothing here accepts an elapsed clock.
 *
 * The sky is not modelled here either: a star only becomes permanent when the caller persists the
 * returned [StarAward]. Rolling the day over drops the counters — and with them today's free minutes —
 * but can never touch a star that was already lit.
 *
 * Every number comes from [EconomyRules] (§3.8) and [EngineRules] (§3.6); none is repeated here.
 */
class DefaultStarEconomy : StarEconomy {

    /**
     * Starts a new logical day when [logicalDay] moves forward (§3.7 rolls over at 04:00 local time).
     *
     * Returns a fresh [EconomyDay]: counters, the leave watermark, the free-scroll spend and the quick
     * needs all reset. Lit stars are untouched because they do not live in [EconomyDay] — they live in
     * the sky — so this is the whole of "unused free minutes vanish at 04:00, but the stars stay" (W5).
     *
     * A [logicalDay] that is not greater than the day's own (the wall clock was turned back, or the
     * caller is repeating itself) returns [day] unchanged: rewinding must not refill today's caps.
     */
    override fun rollover(day: EconomyDay, logicalDay: Int): EconomyDay {
        if (logicalDay <= day.logicalDay) return day
        return EconomyDay(logicalDay = logicalDay)
    }

    /**
     * Whether a Leave right now would create a kindle — the value behind `InterceptUi.leaveEarnsStar`,
     * so the button can read "Leave, light a star" instead of "Leave" (E1, §3.8).
     *
     * This is the grant-free half of [onLeave]: it answers true when the sleep gate, the pending kindle,
     * the rate limit and the caps allow a star. It cannot see a FREE grant, because the contract passes
     * no grant here — the engine must suppress [StarKind.LEAVE] for a leave out of a FREE grant itself
     * (CONTRACT-GAP 3/3).
     */
    override fun peekLeave(day: EconomyDay, window: WindowKind, nowWall: Long, pendingKindles: Int): Boolean {
        if (window == WindowKind.SLEEP_GATE) return false
        if (pendingKindles >= EconomyRules.MAX_PENDING_KINDLES) return false
        if (isRateLimited(day, nowWall)) return false
        if (day.litLeave >= EconomyRules.LEAVE_DAILY_CAP) return false
        if (day.litTotal >= EconomyRules.DAILY_CAP) return false
        return true
    }

    /**
     * The Leave decision (E1, E2, E3, E9).
     *
     * E1 and E2 hand back a [LeaveDecision.Kindle]: nothing is awarded until
     * [confirmKindle] fires [EconomyRules.KINDLE_CONFIRM_MS] later, because the star is for *staying*
     * away. E3 (DoneEarly, or the HELD grace running out) lights at once. E9 (the onboarding welcome)
     * lights at once and counts toward nothing.
     *
     * Refusals, in the order they are checked, and all of them silent:
     *  - `SLEEP_GATE` — no leave-stars at 2 AM; the night pays once at Fajr through E7;
     *  - `NOT_ELIGIBLE` — KEPT_WORD without a STAY grant, DONE_EARLY without a STAY grant that still has
     *    enough remaining, or any leave out of a FREE/QUICK grant;
     *  - `PENDING` — a kindle is already waiting to light;
     *  - `RATE_LIMIT`, `LEAVE_CAP`, `DAILY_CAP` — the three shared caps of E1–E3.
     *
     * Note that this cannot roll the day: it stamps the award with `day.logicalDay`. Call [rollover]
     * first, exactly as the engine does at the top of every input.
     */
    override fun onLeave(
        day: EconomyDay,
        leave: LeaveKind,
        window: WindowKind,
        nowWall: Long,
        pendingKindles: Int,
        grant: Grant?,
    ): Pair<EconomyDay, LeaveDecision> {
        // E9 is recorded by onboarding, not by the engine, and is independent of every window and cap.
        if (leave == LeaveKind.WELCOME) {
            val award = awardOf(StarKind.WELCOME, nowWall, day.logicalDay, window, grant?.firstPackage, grant?.id)
            return day to LeaveDecision.LitNow(award)
        }

        if (window == WindowKind.SLEEP_GATE) return day to LeaveDecision.None(NoStarReason.SLEEP_GATE)

        if (!isEligible(leave, grant)) return day to LeaveDecision.None(NoStarReason.NOT_ELIGIBLE)

        // DONE_EARLY is lit at once, so a waiting kindle does not stand in its way.
        if (leave != LeaveKind.DONE_EARLY && pendingKindles >= EconomyRules.MAX_PENDING_KINDLES) {
            return day to LeaveDecision.None(NoStarReason.PENDING)
        }

        val refusal: NoStarReason? = capRefusal(day, nowWall)
        if (refusal != null) return day to LeaveDecision.None(refusal)

        if (leave == LeaveKind.DONE_EARLY) {
            val award = awardOf(
                kind = StarKind.DONE_EARLY,
                nowWall = nowWall,
                logicalDay = day.logicalDay,
                window = window,
                sourcePackage = grant?.firstPackage,
                sourceRef = grant?.id,
            )
            return withLeaveStar(day, nowWall, 1) to LeaveDecision.LitNow(award)
        }

        return day to LeaveDecision.Kindle(EconomyRules.KINDLE_CONFIRM_MS)
    }

    /**
     * Lights the star a kindle was holding (E1, E2).
     *
     * The caller decides *when* this is due — [Kindle.confirmAtElapsed] is elapsed time, which this
     * layer never sees — and passes the wall clock only so the award can be stamped. Caps are re-checked
     * here because the day may have filled up during the three quiet minutes; a star past a cap is
     * dropped silently and the day comes back untouched. The rate limit is *not* re-checked: the kindle
     * was granted at leave time, and taking it back afterwards would punish a star that was already
     * promised.
     */
    override fun confirmKindle(
        day: EconomyDay,
        kindle: Kindle,
        nowWall: Long,
        logicalDay: Int,
    ): Pair<EconomyDay, StarAward?> {
        val kind: StarKind = starKindFor(kindle.leave)

        // A welcome star is never held by a kindle; if one arrives anyway it books like E9 does.
        if (kind == StarKind.WELCOME) {
            return day to awardOf(kind, nowWall, logicalDay, kindle.window, kindle.sourcePackage, kindle.id)
        }

        if (day.litLeave >= EconomyRules.LEAVE_DAILY_CAP) return day to null
        if (day.litTotal >= EconomyRules.DAILY_CAP) return day to null

        val award = awardOf(kind, nowWall, logicalDay, kindle.window, kindle.sourcePackage, kindle.id)
        return withLeaveStar(day, nowWall, 1) to award
    }

    /**
     * The focus award at the end of a session (E4, E5, E6).
     *
     * `stars = (elapsed ≥ 5 min ? 1 : 0) + floor(elapsed / 15 min) + (completed && planned ≥ 25 min ? 1 : 0)`,
     * capped by what is left of [EconomyRules.FOCUS_DAILY_CAP] and of [EconomyRules.DAILY_CAP]. Each star
     * is a separate award so the sky can light them one by one; the finishing star is the last, and it
     * keeps its slot when a cap truncates the award.
     *
     * Ending a 45-minute session at 20 minutes yields 2 (no finish star); 25 completed yields 3.
     */
    override fun onFocusEnded(
        day: EconomyDay,
        focus: FocusState,
        completed: Boolean,
        nowWall: Long,
        logicalDay: Int,
    ): Pair<EconomyDay, List<StarAward>> {
        val earned: Int = focusStarCount(focus.plannedMs, focus.elapsedMs, completed)
        if (earned <= 0) return day to emptyList()

        val room: Int = minOf(
            (EconomyRules.FOCUS_DAILY_CAP - day.litFocus).coerceAtLeast(0),
            (EconomyRules.DAILY_CAP - day.litTotal).coerceAtLeast(0),
        )
        if (room <= 0) return day to emptyList()

        val stars: Int = minOf(earned, room)
        val finishStar: Boolean =
            completed && focus.plannedMs >= EconomyRules.FOCUS_FINISH_MIN_PLANNED_MS && stars > 0
        val blockStars: Int = if (finishStar) stars - 1 else stars

        val awards: List<StarAward> = buildList {
            repeat(blockStars) {
                add(awardOf(StarKind.FOCUS, nowWall, logicalDay, WindowKind.NONE, null, focus.id))
            }
            if (finishStar) {
                add(awardOf(StarKind.FOCUS_FINISH, nowWall, logicalDay, WindowKind.NONE, null, focus.id))
            }
        }

        val lit: EconomyDay = day.copy(litFocus = day.litFocus + stars, litTotal = day.litTotal + stars)
        return lit to awards
    }

    /**
     * The star a protected window pays when it ends (E7, E8).
     *
     * A quiet night is worth [EconomyRules.QUIET_NIGHT_STARS] and a quiet morning
     * [EconomyRules.PROTECTED_MORNING_STARS], but this signature can only carry one award
     * (CONTRACT-GAP 1/3). Every star is still booked into [EconomyDay] — so the day's total, the daily
     * cap and the free-scroll balance are right — and the first award is returned. Use
     * [onProtectedWindowEndedAwards] when each star needs its own sky entry.
     *
     * A window is quiet when its ledger entry shows no STAY/FREE/PRAYER_STAY grant, at most
     * [EconomyRules.QUIET_MAX_QUICK_NEEDS] quick minute, and no reconciled guarded usage; a window with
     * no ledger entry at all is quiet by definition. "Once per gate" is the caller's to enforce:
     * [EconomyDay] carries no set of paid windows, so a span delivered twice would pay twice.
     */
    override fun onProtectedWindowEnded(
        day: EconomyDay,
        span: ProtectedSpan,
        entry: LedgerEntry?,
        nowWall: Long,
        logicalDay: Int,
    ): Pair<EconomyDay, StarAward?> {
        val (updated, awards) = onProtectedWindowEndedAwards(day, span, entry, nowWall, logicalDay)
        return updated to awards.firstOrNull()
    }

    /**
     * E7/E8 with one award per star, for callers that persist a star per award (CONTRACT-GAP 1/3).
     *
     * The stars are booked into [EconomyDay] exactly as [onProtectedWindowEnded] books them; call one
     * or the other, never both, for the same span.
     */
    fun onProtectedWindowEndedAwards(
        day: EconomyDay,
        span: ProtectedSpan,
        entry: LedgerEntry?,
        nowWall: Long,
        logicalDay: Int,
    ): Pair<EconomyDay, List<StarAward>> {
        val kind: StarKind = when (span.kind) {
            WindowKind.SLEEP_GATE -> StarKind.QUIET_NIGHT
            WindowKind.MORNING -> StarKind.PROTECTED_MORNING
            WindowKind.PRAYER, WindowKind.NONE -> return day to emptyList()
        }
        val full: Int = when (kind) {
            StarKind.QUIET_NIGHT -> EconomyRules.QUIET_NIGHT_STARS
            else -> EconomyRules.PROTECTED_MORNING_STARS
        }
        if (!isQuiet(span.kind, entry)) return day to emptyList()

        val room: Int = (EconomyRules.DAILY_CAP - day.litTotal).coerceAtLeast(0)
        if (room <= 0) return day to emptyList()

        val stars: Int = minOf(full, room)
        val ref: String = "${span.kind.name}:${span.startWall}"
        val awards: List<StarAward> = List(stars) {
            awardOf(kind, nowWall, logicalDay, span.kind, null, ref)
        }
        return day.copy(litTotal = day.litTotal + stars) to awards
    }

    /**
     * Free scroll left for the rest of the logical day: one star lit today is worth
     * [EconomyRules.FREE_MS_PER_STAR], the day's total is capped at
     * [EconomyRules.FREE_SCROLL_DAILY_MAX_MS], and what was already spent is deducted (W5).
     *
     * The balance is derived from the day's counters, so it resets to zero at rollover without anything
     * else happening — the stars in the sky are unaffected.
     */
    override fun freeScrollAvailableMs(day: EconomyDay): Long {
        val earnedMs: Long = minOf(
            day.litTotal.toLong() * EconomyRules.FREE_MS_PER_STAR,
            EconomyRules.FREE_SCROLL_DAILY_MAX_MS,
        )
        return (earnedMs - day.freeUsedMs).coerceAtLeast(0L)
    }

    /** Quick needs left today: [EngineRules.QUICK_NEEDS_PER_DAY] minus the minutes already spent. */
    override fun quickNeedsLeft(day: EconomyDay): Int =
        (EngineRules.QUICK_NEEDS_PER_DAY - day.quickNeedsUsed).coerceIn(0, EngineRules.QUICK_NEEDS_PER_DAY)

    /**
     * What a completed focus of [plannedMin] minutes would light, given the focus stars already lit
     * today — the number the Focus screen shows before the session starts (§3.8).
     *
     * It is the earning formula applied to a session that runs its full length and finishes, minus what
     * today's focus cap has already taken: 5 → 1, 15 → 2, 25 → 3, 45 → 5, 60 → 6, 90 → 8.
     */
    override fun projectFocusStars(plannedMin: Int, alreadyLitFocusToday: Int): Int {
        if (plannedMin <= 0) return 0
        val plannedMs: Long = plannedMin * MINUTE_MS
        val raw: Int = focusStarCount(plannedMs, plannedMs, completed = true)
        val room: Int = (EconomyRules.FOCUS_DAILY_CAP - alreadyLitFocusToday).coerceAtLeast(0)
        return minOf(raw, room)
    }

    // ── rules ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * E1/E2 eligibility. A FREE grant is the reward already and a QUICK grant ends on TIME_UP, which is
     * not one of the surfaces E1 pays for; with no grant (the normal prompting case) or a running stay,
     * the leave is worth a star. E2 is narrower: KEPT_WORD only ends a STAY.
     */
    private fun isEligible(leave: LeaveKind, grant: Grant?): Boolean = when (leave) {
        LeaveKind.KEPT_WORD -> grant?.kind == GrantKind.STAY
        LeaveKind.DONE_EARLY -> hasDoneEarlyRemainder(grant)
        LeaveKind.LEFT_PROMPT, LeaveKind.LEFT_GESTURE, LeaveKind.SCREEN_OFF ->
            grant == null || (grant.kind != GrantKind.FREE && grant.kind != GrantKind.QUICK)
        LeaveKind.WELCOME -> true
    }

    /**
     * E3: only a STAY, and only with `remaining ≥ max(2 min, 50 % of granted)` left on it — the two
     * halves of the rule are [EconomyRules.DONE_EARLY_MIN_REMAINING_MS] and
     * [EconomyRules.DONE_EARLY_MIN_REMAINING_FRACTION].
     */
    private fun hasDoneEarlyRemainder(grant: Grant?): Boolean {
        if (grant == null || grant.kind != GrantKind.STAY) return false
        val remaining: Long = grant.grantedMs - grant.consumedMs
        val threshold: Long = maxOf(
            EconomyRules.DONE_EARLY_MIN_REMAINING_MS,
            (grant.grantedMs * EconomyRules.DONE_EARLY_MIN_REMAINING_FRACTION).toLong(),
        )
        return remaining >= threshold
    }

    /**
     * The three caps shared by E1–E3, in the order §3.8 lists them. `lastLeaveLitWall` is a wall clock,
     * so a clock turned *back* only ever lengthens the wait — it can never unlock a star early.
     */
    private fun capRefusal(day: EconomyDay, nowWall: Long): NoStarReason? = when {
        isRateLimited(day, nowWall) -> NoStarReason.RATE_LIMIT
        day.litLeave >= EconomyRules.LEAVE_DAILY_CAP -> NoStarReason.LEAVE_CAP
        day.litTotal >= EconomyRules.DAILY_CAP -> NoStarReason.DAILY_CAP
        else -> null
    }

    private fun isRateLimited(day: EconomyDay, nowWall: Long): Boolean {
        val lastLit: Long = day.lastLeaveLitWall ?: return false
        return nowWall - lastLit < EconomyRules.LEAVE_RATE_LIMIT_MS
    }

    /** E7/E8: no grants, at most [EconomyRules.QUIET_MAX_QUICK_NEEDS] quick minute, no reconciled usage. */
    private fun isQuiet(kind: WindowKind, entry: LedgerEntry?): Boolean {
        if (entry == null) return true
        // An entry describing a different window says nothing about this one.
        if (entry.kind != kind) return true
        return entry.grants == 0 &&
            entry.quickNeeds <= EconomyRules.QUIET_MAX_QUICK_NEEDS &&
            !entry.reconciledUsage
    }

    private fun starKindFor(leave: LeaveKind): StarKind = when (leave) {
        LeaveKind.LEFT_PROMPT, LeaveKind.LEFT_GESTURE, LeaveKind.SCREEN_OFF -> StarKind.LEAVE
        LeaveKind.KEPT_WORD -> StarKind.KEPT_WORD
        LeaveKind.DONE_EARLY -> StarKind.DONE_EARLY
        LeaveKind.WELCOME -> StarKind.WELCOME
    }

    /** The focus formula of §3.8, before any cap. */
    private fun focusStarCount(plannedMs: Long, elapsedMs: Long, completed: Boolean): Int {
        val elapsed: Long = elapsedMs.coerceAtLeast(0L)
        val first: Int = if (elapsed >= EconomyRules.FOCUS_FIRST_STAR_MS) 1 else 0
        val blocks: Int = (elapsed / EconomyRules.FOCUS_BLOCK_MS).toInt()
        val finish: Int = if (completed && plannedMs >= EconomyRules.FOCUS_FINISH_MIN_PLANNED_MS) 1 else 0
        return first + blocks + finish
    }

    private fun withLeaveStar(day: EconomyDay, nowWall: Long, stars: Int): EconomyDay = day.copy(
        litLeave = day.litLeave + stars,
        litTotal = day.litTotal + stars,
        lastLeaveLitWall = nowWall,
    )

    private fun awardOf(
        kind: StarKind,
        nowWall: Long,
        logicalDay: Int,
        window: WindowKind,
        sourcePackage: String?,
        sourceRef: String?,
    ): StarAward = StarAward(
        kind = kind,
        litWall = nowWall,
        logicalDay = logicalDay,
        window = window,
        sourcePackage = sourcePackage,
        sourceRef = sourceRef,
    )
}
