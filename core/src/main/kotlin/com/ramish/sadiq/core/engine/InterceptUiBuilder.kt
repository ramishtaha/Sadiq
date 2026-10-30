package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionContext
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * Everything the surface needs from the outside world that is not in [EngineState]: the two economy
 * balances, the reflection count and the leave-star preview.
 */
data class UiFacts(
    val freeScrollAvailableMs: Long,
    val quickNeedsLeft: Int,
    val reflectionOpens: Int,
    val leaveEarnsStar: Boolean,
)

/**
 * Turns engine state into the immutable description the interception surface renders (§4.4).
 *
 * The builder is where the *options* of a surface are decided, because the reducer has to validate
 * the very same list: `T09` accepts a Stay only when `m ∈ stayOptionsMin`, so both sides read it from
 * here. A surface never offers an option the reducer would refuse.
 *
 * Rule zero is enforced structurally: Leave is not a field. Every surface built here has it, and no
 * branch can remove it.
 */
object InterceptUiBuilder {


    fun build(
        state: EngineState,
        prompt: Prompt,
        ctx: GuardContext,
        now: TimeSnapshot,
        facts: UiFacts,
        suggestion: Suggestion?,
    ): InterceptUi {
        val kind = prompt.kind
        val restActive = (state.rest?.remainingMs ?: 0L) > 0L
        // A TIME_UP surface still holds the grant that ran out, so the surface can name it.
        val timeUpKind = if (kind == SurfaceKind.TIME_UP) state.grant?.kind else null
        val stay = stayOptions(kind, state, ctx)
        val freeChips = freeScrollOptions(kind, state, ctx, facts)
        val quickAllowed = SurfaceResolver.allowsQuickNeed(kind, restActive, facts.quickNeedsLeft)
        val grant = state.grant
        // "Open anyway" (§A.1). A door that is not offered is null in every field except the one
        // that names when it comes back, so the surface cannot render a button the engine would
        // refuse to honour.
        val door = overrideOffer(state, prompt, ctx, facts)

        return InterceptUi(
            kind = kind,
            packageName = prompt.packageName,
            domain = prompt.domain,
            suggestion = suggestion,
            stayOptionsMin = stay,
            stayRequiresHold = kind == SurfaceKind.MORNING,
            resumeRemainingMs = if (kind == SurfaceKind.PAUSE && grant?.pausedSinceElapsed != null) {
                Accounting.grantRemainingMs(grant)
            } else {
                null
            },
            freeScrollOptionsMin = freeChips,
            freeScrollAvailableMs = if (freeChips.isEmpty()) null else facts.freeScrollAvailableMs,
            quickNeedsLeft = if (quickAllowed) facts.quickNeedsLeft else null,
            budgetRemainingMs = state.budget.remainingMs,
            restRemainingMs = state.rest?.remainingMs,
            restEndsWall = state.rest?.let { now.wallMs + it.remainingMs },
            focusRemainingMs = state.focus?.let { (it.plannedMs - it.elapsedMs).coerceAtLeast(0L) },
            prayer = if (kind == SurfaceKind.PRAYER) ctx.window.prayer else null,
            isJumuah = kind == SurfaceKind.PRAYER && ctx.window.isJumuah,
            nextFajrWall = nextFajrAt(ctx.today, now.wallMs),
            windowEndWall = ctx.window.endWall,
            reflectionOpens = if (kind == SurfaceKind.REFLECT) facts.reflectionOpens else null,
            reflectReason = prompt.reflectReason,
            timeUpKind = timeUpKind,
            leaveEarnsStar = when (kind) {
                // No leave star while focusing: the focus session is what pays (T15), and the engine
                // refuses the award for exactly this kind on every exit path, so this `false` and the
                // kindle gate in `applyLeave` are one rule read in two places (A4-09).
                SurfaceKind.FOCUS_GUARD -> false
                // The self-test promises the first star; onboarding lights it, not the engine (E9).
                SurfaceKind.TEST -> true
                // Leaving a TIME_UP only pays KEPT_WORD, and the economy pays that only for a STAY.
                // After free scroll, one minute or a prayer Stay, a star on the button would be a
                // promise the engine cannot keep (AUDIT-3 B-2).
                SurfaceKind.TIME_UP ->
                    facts.leaveEarnsStar && (state.grant == null || state.grant.kind == GrantKind.STAY)
                else -> facts.leaveEarnsStar
            },
            forceDark = kind == SurfaceKind.SLEEP_GATE,
            testMode = prompt.testMode,
            mode = ctx.config.mode,
            overrideLeft = if (door) overrideLeft(state, ctx) else null,
            overrideWaitMs = if (door) overrideWaitMs(ctx) else null,
            // The confirm view only exists for a door that is actually openable: an arm left over
            // from a door that has gone is not something to draw (§A.1).
            overrideArmedElapsed = prompt.overrideArmedElapsed.takeIf { door },
            overrideStarsAtStake = if (door) overrideStarsAtStake(state, ctx) else null,
            overrideBackAtWall = overrideBackAtWall(state, prompt, ctx, now, facts),
        )
    }

    // ── "Open anyway" (§A.1–A.4) ────────────────────────────────────────────────────────────────

    /** The surfaces that may ever show the door. Every other one has a yes of its own. */
    private val OVERRIDE_KINDS: Set<SurfaceKind> = setOf(
        SurfaceKind.COOLDOWN,
        SurfaceKind.SLEEP_GATE,
        SurfaceKind.TIME_UP,
    )

    /**
     * Whether "Open anyway" may be offered on this surface, right now.
     *
     * The rule of §A.1, in one place, read by the reducer's guards (T36/T37) and by [build], so the
     * button on screen is always a door the engine will open. The door is a last resort: it appears
     * only where the surface offers no "yes" of its own — a rest, Bedtime, or a Time's up with
     * nothing left to choose — never at prayer time, never during Focus, never in the self-test, and
     * never while either cap is spent.
     *
     * @param state the engine state the surface belongs to.
     * @param prompt the question on screen.
     * @param ctx the window and settings at this instant.
     * @param facts the economy balances, needed because free scroll is what the door defers to.
     */
    fun overrideOffer(state: EngineState, prompt: Prompt, ctx: GuardContext, facts: UiFacts): Boolean =
        overrideEligible(state, prompt, ctx, facts) &&
            overrideDayLeft(state) > 0 &&
            overrideNightLeft(state, ctx) > 0

    /**
     * Everything [overrideOffer] requires except the two caps.
     *
     * Split out because a spent cap is not "no door here": it is the one case that shows a caption
     * instead of the button (§A.6), and that caption needs the reason the button is missing.
     */
    private fun overrideEligible(
        state: EngineState,
        prompt: Prompt,
        ctx: GuardContext,
        facts: UiFacts,
    ): Boolean {
        if (prompt.kind !in OVERRIDE_KINDS) return false
        if (prompt.testMode) return false
        if (state.focus != null) return false
        if (ctx.window.kind == WindowKind.PRAYER) return false
        // A surface with a yes of its own keeps it alone: a second yes would make the first one
        // pointless (A.1).
        if (stayOptions(prompt.kind, state, ctx).isNotEmpty()) return false
        if (freeScrollOptions(prompt.kind, state, ctx, facts).isNotEmpty()) return false
        return true
    }

    /** Uses left today, counting the current Bedtime's own cap. Null is [build]'s job, not this one. */
    private fun overrideLeft(state: EngineState, ctx: GuardContext): Int =
        minOf(overrideDayLeft(state), overrideNightLeft(state, ctx))

    private fun overrideDayLeft(state: EngineState): Int =
        (EngineRules.OVERRIDE_DAY_CAP - state.economy.overridesUsed).coerceAtLeast(0)

    /**
     * The night cap only exists inside a Bedtime window; everywhere else it cannot bind, so it must
     * report "unlimited" rather than its own limit — returning the cap made every daytime surface
     * report `min(dayLeft, 1)` and show one use left when there were two.
     */
    private fun overrideNightLeft(state: EngineState, ctx: GuardContext): Int {
        if (ctx.window.kind != WindowKind.SLEEP_GATE) return Int.MAX_VALUE
        val used = ledgerRow(state, ctx)?.overrides ?: 0
        return (EngineRules.OVERRIDE_NIGHT_CAP - used).coerceAtLeast(0)
    }

    /**
     * How long the door asks him to wait before the hold: 30 s by day, 60 s at Bedtime (§A.2).
     *
     * The reducer measures the wait with the same function, so the countdown on screen and the
     * guard that opens the grant can never disagree.
     */
    fun overrideWaitMs(ctx: GuardContext): Long =
        if (ctx.window.kind == WindowKind.SLEEP_GATE) {
            EngineRules.OVERRIDE_WAIT_NIGHT_MS
        } else {
            EngineRules.OVERRIDE_WAIT_MS
        }

    /**
     * The wall time the door is back, when a cap is what is stopping it, else null (§A.6).
     *
     * The day cap resets at the next 04:00, which is the start of the next logical day; the night cap
     * resets at Fajr, which is when the Bedtime window ends. A spent night cap is checked first
     * because it is spent at most once and always binds until the gate is over — including across
     * the 04:00 rollover that empties the day counter in the middle of the same night.
     */
    fun overrideBackAtWall(
        state: EngineState,
        prompt: Prompt,
        ctx: GuardContext,
        now: TimeSnapshot,
        facts: UiFacts,
    ): Long? {
        if (!overrideEligible(state, prompt, ctx, facts)) return null
        if (overrideNightLeft(state, ctx) <= 0) return nextFajrAt(ctx.today, now.wallMs)
        if (overrideDayLeft(state) <= 0) return LogicalDays.startWall(state.economy.logicalDay + 1, now.zoneId)
        return null
    }

    /**
     * The window stars the door would cost: tonight's 3 quiet-night stars, this morning's 2 (§A.3).
     *
     * Stated only while they are still possible. Once the ledger says the window is not quiet — a
     * grant, two quick minutes, a reconciled use, or an earlier override — they are already gone, and
     * naming them would be a second charge for the same thing.
     */
    fun overrideStarsAtStake(state: EngineState, ctx: GuardContext): Int? {
        val kind = when (ctx.window.kind) {
            WindowKind.SLEEP_GATE -> StarKind.QUIET_NIGHT
            WindowKind.MORNING -> StarKind.PROTECTED_MORNING
            else -> return null
        }
        return if (isQuiet(state, ctx)) fullWindowStars(kind) else null
    }

    private fun fullWindowStars(kind: StarKind): Int = when (kind) {
        StarKind.QUIET_NIGHT -> EconomyRules.QUIET_NIGHT_STARS
        else -> EconomyRules.PROTECTED_MORNING_STARS
    }

    /**
     * E7/E8's quiet test, read on the surface side for the same ledger row the economy will judge:
     * no grant, at most one quick minute, no reconciled usage. An override counts in `grants`, so
     * after one the window is no longer quiet — which is exactly the disclosure rule above.
     */
    private fun isQuiet(state: EngineState, ctx: GuardContext): Boolean {
        val entry = ledgerRow(state, ctx) ?: return true
        return entry.grants == 0 &&
            entry.quickNeeds <= EconomyRules.QUIET_MAX_QUICK_NEEDS &&
            !entry.reconciledUsage
    }

    /** The ledger row of the window in force, or null when the window has nothing recorded yet. */
    private fun ledgerRow(state: EngineState, ctx: GuardContext): LedgerEntry? {
        val start = ctx.window.startWall ?: return null
        return state.ledger.firstOrNull { it.kind == ctx.window.kind && it.startWall == start }
    }

    /**
     * The Stay chips for a surface, in minutes.
     *
     * PRAYER always has Stay (rule zero: no surface may withhold it in a prayer window) and caps it at
     * [EngineRules.PRAYER_STAY_MAX_MIN], and so does every other surface that offers Stay while that
     * window is open: a prompt shown before the adhan must not offer fifteen minutes through Maghrib,
     * and a grant that ran out at Asr must still be able to say "a little more" (A4-04). MORNING offers
     * one two-minute hold. The harder surfaces (sleep gate, focus guard, test) offer no Stay at all —
     * their soft option is the one-minute quick need or ending the focus, and the resolver never leaves
     * one of them on screen inside a prayer window.
     */
    fun stayOptions(kind: SurfaceKind, state: EngineState, ctx: GuardContext): List<Int> = when (kind) {
        SurfaceKind.PRAYER -> prayerChips()
        SurfaceKind.MORNING -> listOf(EngineRules.MORNING_STAY_MIN)
        SurfaceKind.PAUSE, SurfaceKind.REFLECT ->
            if (ctx.window.kind == WindowKind.PRAYER) prayerChips() else budgetChips(state.budget)
        SurfaceKind.TIME_UP -> timeUpChips(state, ctx)
        SurfaceKind.COOLDOWN,
        SurfaceKind.SLEEP_GATE,
        SurfaceKind.FOCUS_GUARD,
        SurfaceKind.TEST,
        -> emptyList()
    }

    private fun timeUpChips(state: EngineState, ctx: GuardContext): List<Int> {
        // Rule zero: a prayer window never withholds Stay, whatever kind of time just ran out. The
        // prayer chips are already capped, so a Stay chosen here cannot reach into the window's end.
        if (ctx.window.kind == WindowKind.PRAYER) return prayerChips()
        val expired = state.grant?.kind
        val expiredStay = expired == null || expired == GrantKind.STAY
        if (!expiredStay) return emptyList()
        if (SurfaceResolver.protectedWindowActive(ctx, state.focus != null)) return emptyList()
        if (Accounting.isBudgetExhausted(state.budget)) return emptyList()
        return budgetChips(state.budget)
    }

    /** Free-scroll chips: the fixed chips filtered by what is left today, plus "all" when it fits. */
    fun freeScrollOptions(
        kind: SurfaceKind,
        state: EngineState,
        ctx: GuardContext,
        facts: UiFacts,
    ): List<Int> {
        if (kind !in SurfaceResolver.FREE_SCROLL_KINDS) return emptyList()
        if (SurfaceResolver.protectedWindowActive(ctx, state.focus != null)) return emptyList()
        if (facts.freeScrollAvailableMs <= 0L) return emptyList()
        val availableMin = (facts.freeScrollAvailableMs / EngineRules.MINUTE_MS).toInt()
        if (availableMin <= 0) return emptyList()
        val fixed = EngineRules.FREE_CHIPS_MIN
        val chips = fixed.filter { it in 1..availableMin }
        val all = if (availableMin !in fixed) listOf(availableMin) else emptyList()
        return (chips + all).distinct().sorted().take(3)
    }

    private fun prayerChips(): List<Int> {
        val cap = EngineRules.PRAYER_STAY_MAX_MIN
        val base = EngineRules.STAY_CHIPS_MIN.filter { it in 1..cap }
        return (base.ifEmpty { listOf(cap) } + cap).distinct().sorted().take(EngineRules.MAX_STAY_CHIPS)
    }

    private fun budgetChips(budget: Budget): List<Int> {
        val remainingMin = (budget.remainingMs / EngineRules.MINUTE_MS).toInt()
        if (remainingMin <= 0) return emptyList()
        val fixed = EngineRules.STAY_CHIPS_MIN
        val base = fixed.filter { it <= remainingMin }
        val all = if (remainingMin !in fixed) listOf(remainingMin) else emptyList()
        return (base + all).distinct().sorted().take(EngineRules.MAX_STAY_CHIPS)
    }

    /**
     * The Fajr the sleep-gate line names: the next one at or after [nowWall].
     *
     * [PrayerDay.nextFajr] is tomorrow's adhan by definition, so reading it alone said "24 h 44 min
     * from now" at 4:39 AM with Fajr at 5:24 — the one hour the gate is most likely to be read. Before
     * today's adhan the honest answer is today's [PrayerDay.fajr]; after it, tomorrow's.
     */
    private fun nextFajrAt(day: PrayerDay, nowWall: Long): Long =
        if (day.fajr > nowWall) day.fajr else day.nextFajr

    /** The suggester's input, assembled from the context the schedule already computed. */
    fun suggestionContext(
        state: EngineState,
        ctx: GuardContext,
        now: TimeSnapshot,
        reason: ReflectionReason?,
    ): SuggestionContext = SuggestionContext(
        window = ctx.window,
        nowWall = now.wallMs,
        minuteOfDay = ctx.minuteOfDay,
        nextPrayer = ctx.nextPrayer,
        nextPrayerWall = ctx.nextPrayerWall,
        maghribWall = ctx.today.maghrib,
        nextFajrWall = ctx.today.nextFajr,
        reason = reason,
        gymHintEnabled = ctx.config.gymHintEnabled,
        openIndexToday = state.recentOpensWall.size,
        logicalDay = ctx.logicalDay,
    )
}
