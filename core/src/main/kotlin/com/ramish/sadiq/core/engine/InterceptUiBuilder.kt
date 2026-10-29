package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionContext
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.WindowKind
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
        )
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
