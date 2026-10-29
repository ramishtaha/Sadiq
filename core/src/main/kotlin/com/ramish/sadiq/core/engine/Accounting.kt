package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * The accounting of §3.6: it runs at the start of every input, before the transition.
 *
 * Four things happen here, always in this order:
 * 1. a grant consumes elapsed time (only while it is genuinely in front, awake, in the same boot);
 * 2. a rest decays (by elapsed inside a boot, by wall delta across one);
 * 3. focus accrues;
 * 4. the budget refills.
 *
 * Nothing in this file reads a clock; every duration comes from the two clocks in [TimeSnapshot].
 * `budget.lastGuardedUseElapsed` is the anchor for "no guarded use for `mode.cooldownMin`": it is
 * refreshed whenever a guarded app is actually in front, so an idle evening refills without a rest.
 */
object Accounting {

    /** Full budget for a mode, in milliseconds. */
    fun fullBudgetMs(mode: Mode): Long = mode.budgetMin * EngineRules.MINUTE_MS

    /** Rest length for a mode, in milliseconds. */
    fun restMs(mode: Mode): Long = mode.cooldownMin * EngineRules.MINUTE_MS

    /** A budget below [EngineRules.BUDGET_EPSILON_MS] counts as exhausted (§3.6). */
    fun isBudgetExhausted(budget: Budget): Boolean =
        budget.remainingMs < EngineRules.BUDGET_EPSILON_MS

    /** Time left in a grant, never negative. */
    fun grantRemainingMs(grant: Grant): Long =
        (grant.grantedMs - grant.consumedMs).coerceAtLeast(0L)

    /** Starts the cooldown rest that follows an exhausted Stay budget. */
    fun startRest(state: EngineState, now: TimeSnapshot, ctx: GuardContext): EngineState =
        state.copy(rest = Rest(remainingMs = restMs(ctx.config.mode), lastAccountedElapsed = now.elapsedMs))

    /** Applies one input's worth of accrual and the once-per-boot repairs. */
    fun step(state: EngineState, now: TimeSnapshot, ctx: GuardContext): EngineState {
        val reboot = now.bootCount != state.bootCount
        var st = state

        st = accrueGrant(st, now, reboot)
        st = accrueRest(st, now, ctx, reboot)
        st = accrueFocus(st, now, reboot)
        st = refillBudget(st, now, ctx.config.mode)

        if (st.screenOn && st.foregroundGuarded) {
            st = st.copy(budget = st.budget.copy(lastGuardedUseElapsed = now.elapsedMs))
        }
        if (reboot) {
            // A kindle is anchored to the previous boot's elapsed clock; after a reboot it can only
            // fire at a random later moment, so it is dropped rather than confirmed late.
            st = st.copy(bootCount = now.bootCount, kindles = emptyList())
        }
        return st
    }

    private fun accrueGrant(state: EngineState, now: TimeSnapshot, reboot: Boolean): EngineState {
        val grant = state.grant ?: return state
        val counting = state.phase == Phase.GRANTED && state.screenOn && state.foregroundGuarded
        val delta =
            if (reboot || !counting) 0L
            else (now.elapsedMs - grant.lastAccountedElapsed).coerceAtLeast(0L)
        val consumed = (grant.consumedMs + delta).coerceAtMost(grant.grantedMs)
        val drainsBudget = grant.kind == GrantKind.STAY || grant.kind == GrantKind.PRAYER_STAY
        val budget =
            if (counting && delta > 0L && drainsBudget) {
                state.budget.copy(remainingMs = (state.budget.remainingMs - delta).coerceAtLeast(0L))
            } else {
                state.budget
            }
        return state.copy(
            grant = grant.copy(consumedMs = consumed, lastAccountedElapsed = now.elapsedMs),
            budget = budget,
        )
    }

    private fun accrueRest(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        reboot: Boolean,
    ): EngineState {
        val rest = state.rest ?: return state
        // Across a reboot the elapsed clock restarted, so the wall delta is the honest measure of
        // how long the phone was actually off (§3.6 accounting).
        val delta =
            if (reboot) (now.wallMs - state.lastSeenWall).coerceAtLeast(0L)
            else (now.elapsedMs - rest.lastAccountedElapsed).coerceAtLeast(0L)
        val remaining = (rest.remainingMs - delta).coerceAtLeast(0L)
        return if (remaining <= 0L) {
            state.copy(
                rest = null,
                budget = Budget(remainingMs = fullBudgetMs(ctx.config.mode), lastGuardedUseElapsed = null),
            )
        } else {
            state.copy(rest = rest.copy(remainingMs = remaining, lastAccountedElapsed = now.elapsedMs))
        }
    }

    private fun accrueFocus(state: EngineState, now: TimeSnapshot, reboot: Boolean): EngineState {
        val focus = state.focus ?: return state
        val delta =
            if (reboot) (now.wallMs - state.lastSeenWall).coerceAtLeast(0L)
            else (now.elapsedMs - focus.lastAccountedElapsed).coerceAtLeast(0L)
        val elapsed = (focus.elapsedMs + delta).coerceAtMost(focus.plannedMs)
        return state.copy(focus = focus.copy(elapsedMs = elapsed, lastAccountedElapsed = now.elapsedMs))
    }

    /**
     * The budget refills to full when a rest ends (handled in [accrueRest]) or when there has been no
     * guarded use for `mode.cooldownMin` (§3.6 accounting).
     */
    fun refillBudget(state: EngineState, now: TimeSnapshot, mode: Mode): EngineState {
        if (state.rest != null || state.grant != null) return state
        val full = fullBudgetMs(mode)
        if (state.budget.remainingMs >= full) return state
        val last = state.budget.lastGuardedUseElapsed
        val idleFor = if (last == null) Long.MAX_VALUE else now.elapsedMs - last
        if (idleFor < restMs(mode)) return state
        return state.copy(
            budget = Budget(remainingMs = full, lastGuardedUseElapsed = null),
        )
    }

    /**
     * T32: charges reconciled usage spans to the budget (or the rest, if one is running) and marks
     * every protected window they touch as having real usage, which disqualifies the quiet-night and
     * protected-morning stars.
     */
    fun chargeSpans(state: EngineState, spans: List<UsageSpan>, ctx: GuardContext): EngineState {
        if (spans.isEmpty()) return state
        var budget = state.budget
        var rest = state.rest
        var ledger = state.ledger

        for (span in spans.sortedBy { it.startWall }) {
            if (!Guardedness.isGuardedPackage(span.packageName, ctx.config)) continue
            val ms = (span.endWall - span.startWall).coerceAtLeast(0L)
            if (ms == 0L) continue
            if (rest != null) {
                val remaining = (rest.remainingMs - ms).coerceAtLeast(0L)
                if (remaining <= 0L) {
                    rest = null
                    budget = Budget(
                        remainingMs = fullBudgetMs(ctx.config.mode),
                        lastGuardedUseElapsed = null,
                    )
                } else {
                    rest = rest.copy(remainingMs = remaining)
                }
            } else {
                budget = budget.copy(remainingMs = (budget.remainingMs - ms).coerceAtLeast(0L))
            }
            for (window in protectedWindows(ctx)) {
                if (overlaps(span, window)) {
                    ledger = markReconciled(ledger, window)
                }
            }
        }
        return state.copy(budget = budget, rest = rest, ledger = ledger)
    }

    private fun protectedWindows(ctx: GuardContext): List<ActiveWindow> {
        val current = ctx.window.takeIf {
            it.kind == WindowKind.SLEEP_GATE || it.kind == WindowKind.MORNING
        }
        val ended = ctx.endedProtected.map { ActiveWindow(it.kind, it.startWall, it.endWall) }
        return listOfNotNull(current) + ended
    }

    private fun overlaps(span: UsageSpan, window: ActiveWindow): Boolean {
        val start = window.startWall ?: return false
        val end = window.endWall ?: return false
        return span.startWall < end && span.endWall > start
    }

    /** Adds or updates the ledger row for a protected window, flagging reconciled usage. */
    private fun markReconciled(ledger: List<LedgerEntry>, window: ActiveWindow): List<LedgerEntry> {
        val start = window.startWall ?: return ledger
        val index = ledger.indexOfFirst { it.kind == window.kind && it.startWall == start }
        if (index < 0) {
            return ledger + LedgerEntry(
                kind = window.kind,
                startWall = start,
                grants = 0,
                quickNeeds = 0,
                reconciledUsage = true,
            )
        }
        val row = ledger[index]
        if (row.reconciledUsage) return ledger
        return ledger.toMutableList().also { it[index] = row.copy(reconciledUsage = true) }
    }

    /**
     * Counts a grant or a quick need against the protected window it happened in, so the economy can
     * decide E7 / E8 when the window ends (§3.8).
     */
    fun countProtectedUse(
        state: EngineState,
        ctx: GuardContext,
        grants: Int,
        quickNeeds: Int,
    ): EngineState {
        val kind = ctx.window.kind
        if (kind != WindowKind.SLEEP_GATE && kind != WindowKind.MORNING) return state
        if (grants == 0 && quickNeeds == 0) return state
        val start = ctx.window.startWall ?: return state
        val index = state.ledger.indexOfFirst { it.kind == kind && it.startWall == start }
        val ledger =
            if (index < 0) {
                state.ledger + LedgerEntry(
                    kind = kind,
                    startWall = start,
                    grants = grants,
                    quickNeeds = quickNeeds,
                    reconciledUsage = false,
                )
            } else {
                val row = state.ledger[index]
                state.ledger.toMutableList().also {
                    it[index] = row.copy(grants = row.grants + grants, quickNeeds = row.quickNeeds + quickNeeds)
                }
            }
        return state.copy(ledger = ledger)
    }

    /** Removes the ledger row of a window that has just been handed to the economy. */
    fun dropLedger(state: EngineState, kind: WindowKind, startWall: Long): EngineState =
        state.copy(ledger = state.ledger.filterNot { it.kind == kind && it.startWall == startWall })
}
