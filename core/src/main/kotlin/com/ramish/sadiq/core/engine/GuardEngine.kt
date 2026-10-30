package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.EconomyDay
import com.ramish.sadiq.core.economy.LeaveDecision
import com.ramish.sadiq.core.economy.LeaveKind
import com.ramish.sadiq.core.economy.StarEconomy
import com.ramish.sadiq.core.insight.NextActionSuggester
import com.ramish.sadiq.core.insight.ReflectionPolicy
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * The interception state machine of §3.6 as a pure reducer.
 *
 * Every input goes through the same five stages, in this order:
 * 1. **Accounting** — grant consumption, rest decay, focus accrual, budget refill (`Accounting`).
 * 2. **Day rollover** — T31.
 * 3. **Protected windows that ended** — T30, each one handed to the economy.
 * 4. **Kindle confirmation** — T34, then the test arm's 60 s expiry.
 * 5. **Focus completion** — T29 — and finally the transition the input asks for.
 *
 * The order matters and is deliberate: a kindle that matured before this input is confirmed before a
 * guarded foreground fades it, and a focus that reached its planned length completes before the input
 * that happened to arrive at the same instant is interpreted.
 *
 * Effects are emitted in the order the row lists them, with the bookkeeping records (a closed grant, a
 * lit star) appended where they occur. Nothing here reads a clock, rolls a die or touches the disk:
 * the reducer's only inputs are the state, the input, [TimeSnapshot] and [GuardContext], and the
 * economy and insight rules are injected.
 */
class GuardEngine(
    private val economy: StarEconomy,
    private val reflection: ReflectionPolicy,
    private val suggester: NextActionSuggester,
) : GuardReducer {

    private val resolver: SurfaceResolver = SurfaceResolver(reflection)

    override fun initial(now: TimeSnapshot, ctx: GuardContext): EngineState = EngineState(
        phase = Phase.IDLE,
        foregroundPackage = null,
        foregroundGuarded = false,
        screenOn = true,
        prompt = null,
        grant = null,
        budget = Budget(
            remainingMs = Accounting.fullBudgetMs(ctx.config.mode),
            lastGuardedUseElapsed = null,
        ),
        rest = null,
        focus = null,
        kindles = emptyList(),
        recentOpensWall = emptyList(),
        lastReflectWall = null,
        ledger = emptyList(),
        economy = EconomyDay(logicalDay = ctx.logicalDay),
        testArm = null,
        bootCount = now.bootCount,
        seq = 0L,
        lastSeenWall = now.wallMs,
        lastSeenElapsed = now.elapsedMs,
    )

    override fun reduce(
        state: EngineState,
        input: GuardInput,
        now: TimeSnapshot,
        ctx: GuardContext,
    ): Transition {
        val effects = mutableListOf<Effect>()
        var st = Accounting.step(state, now, ctx)

        st = rollDay(st, now, ctx)
        st = awardEndedWindows(st, now, ctx, effects)
        st = expireTestArm(st, now)
        st = confirmKindles(st, now, ctx, effects)
        st = finishFocusIfDue(st, now, ctx, effects)
        st = dispatch(st, input, now, ctx, effects)
        st = settle(st, now, ctx)

        return Transition(st, effects.toList())
    }

    override fun nextDeadlineElapsed(state: EngineState, now: TimeSnapshot, ctx: GuardContext): Long? =
        Deadlines.next(state, now)

    // ── housekeeping ────────────────────────────────────────────────────────────────────────────

    /** T31: a new logical day rolls the economy and drops opens older than the reflection window. */
    private fun rollDay(state: EngineState, now: TimeSnapshot, ctx: GuardContext): EngineState {
        if (ctx.logicalDay == state.economy.logicalDay) return state
        val window = EngineRules.REFLECT_WINDOW_MS
        return state.copy(
            economy = economy.rollover(state.economy, ctx.logicalDay),
            recentOpensWall = state.recentOpensWall.filter { it > now.wallMs - window },
        )
    }

    /** T30: every protected window that ended since the last input pays out, once. */
    private fun awardEndedWindows(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (ctx.endedProtected.isEmpty()) return state
        var st = state
        for (span in ctx.endedProtected) {
            val entry = st.ledger.firstOrNull { it.kind == span.kind && it.startWall == span.startWall }
            val (day, awards) = economy.onProtectedWindowEnded(
                day = st.economy,
                span = span,
                entry = entry,
                nowWall = now.wallMs,
                logicalDay = ctx.logicalDay,
            )
            st = st.copy(economy = day)
            for (award in awards) effects += Effect.Record(RecordEvent.StarLit(award))
            st = Accounting.dropLedger(st, span.kind, span.startWall)
        }
        return st
    }

    /** T34: a kindle confirms when its three minutes are up; T35 has already faded the ones that must. */
    private fun confirmKindles(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (state.kindles.isEmpty()) return state
        var st = state
        for (kindle in state.kindles) {
            if (now.elapsedMs < kindle.confirmAtElapsed) {
                continue
            }
            val (day, award) = economy.confirmKindle(st.economy, kindle, now.wallMs, ctx.logicalDay)
            st = st.copy(economy = day)
            if (award != null) effects += Effect.Record(RecordEvent.StarLit(award))
            st = st.copy(kindles = st.kindles.filterNot { it.id == kindle.id })
        }
        return st
    }

    private fun expireTestArm(state: EngineState, now: TimeSnapshot): EngineState {
        val arm = state.testArm ?: return state
        return if (now.elapsedMs - arm.armedElapsed >= EngineRules.SELF_TEST_ARM_MS) state.copy(testArm = null) else state
    }

    /** T29: the session reached its planned length; it completes whether or not the app is in front. */
    private fun finishFocusIfDue(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val focus = state.focus ?: return state
        if (focus.elapsedMs < focus.plannedMs) return state
        val (day, awards) = economy.onFocusEnded(
            day = state.economy,
            focus = focus,
            completed = true,
            nowWall = now.wallMs,
            logicalDay = ctx.logicalDay,
        )
        effects += Effect.Record(
            RecordEvent.FocusClosed(focus, now.wallMs, true, awards.size, ctx.logicalDay),
        )
        for (award in awards) effects += Effect.Record(RecordEvent.StarLit(award))
        if (focus.sound != SoundKind.NONE) effects += Effect.StopSound
        effects += Effect.FocusCompleted(awards.size)
        val st = state.copy(focus = null, economy = day)
        // A completed focus must also clear notification id 1. With keep-alive on (the default) the
        // session service does not re-post on its own, so without this the shade keeps a countdown that
        // can only count below zero and whose End button addresses a session that no longer exists.
        effects += Effect.Live(liveAfterFocus(st))
        // If the session ran out under an open FOCUS_GUARD, the surface he is looking at says "You're
        // focusing" and offers "End focus", which lands on endFocusFromPrompt and returns early now
        // that `focus` is null — a button that does nothing. Re-resolve the surface without the
        // session, the same seam T16 uses, so the question on screen is one the engine can still
        // answer. promptOf restarts `shownWall` for the same reason T16 does: this is a new question.
        val prompt = st.prompt
        return if (st.phase == Phase.PROMPTING && prompt != null && prompt.kind == SurfaceKind.FOCUS_GUARD) {
            val kind = resolver.withoutFocus(
                ctx = ctx,
                nowWall = now.wallMs,
                restActive = restActive(st),
                recentOpensWall = st.recentOpensWall,
                lastReflectWall = st.lastReflectWall,
            )
            val next = promptOf(kind, prompt.packageName, prompt.domain, now)
            val updated = st.copy(prompt = next, foregroundGuarded = true)
            effects += Effect.UpdateSurface(ui(updated, next, ctx, now, reason = null))
            updated
        } else {
            st
        }
    }

    // ── dispatch ───────────────────────────────────────────────────────────────────────────────

    private fun dispatch(
        state: EngineState,
        input: GuardInput,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        var st = state

        // T19: Sadiq itself is transparent. The input is not a decision, so it changes nothing and
        // produces no interception effect; whatever was already due (a star, a completed focus) has
        // been emitted by the housekeeping stages above and stands.
        if (input is GuardInput.ForegroundChanged && input.packageName == Guardedness.OWN_PACKAGE) {
            return st
        }

        // A deliberate grant control is handled before the time boundaries, so a tap that lands on
        // the same instant as a boundary is never swallowed by it.
        if (input is GuardInput.Chose) {
            when (input.choice) {
                is Choice.DoneEarly -> if (st.grant != null && st.phase in GRANT_PHASES) {
                    return doneEarly(st, now, ctx, effects)
                }
                is Choice.PauseGrant -> if (st.grant != null && st.phase in GRANT_PHASES) {
                    return pauseGrant(st, now, ctx, effects)
                }
                else -> Unit
            }
        }

        // T21: the grant is spent. Any input notices it.
        if (st.phase == Phase.GRANTED) {
            val grant = st.grant
            if (grant != null && grant.consumedMs >= grant.grantedMs) {
                return timeUp(st, grant, now, ctx, effects)
            }
        }

        // T27: a saved pause is only resumable inside its hour. Checked before a foreground change so
        // an expired pause can never be revived by opening the app again.
        if (st.phase == Phase.PAUSED) {
            val pausedSince = st.grant?.pausedSinceElapsed
            if (pausedSince != null && now.elapsedMs - pausedSince >= EngineRules.PAUSED_MAX_MS) {
                return expirePause(st, GrantEnd.PAUSE_EXPIRED, now, ctx, effects)
            }
        }

        // T26: the grace ran out while he was away. A guarded foreground is excluded on purpose: T03
        // owns that case, and coming back must not earn the "kept his word" star.
        if (st.phase == Phase.HELD && input !is GuardInput.ForegroundChanged) {
            val heldSince = st.grant?.heldSinceElapsed
            if (heldSince != null && now.elapsedMs - heldSince >= EngineRules.HOLD_GRACE_MS) {
                return heldGraceOver(st, now, ctx, effects)
            }
        }

        // T20: the settings changed under a showing surface, so the app is no longer guarded.
        if (st.phase == Phase.PROMPTING) {
            val prompt = st.prompt
            if (prompt != null && !prompt.testMode && !Guardedness.stillGuarded(prompt, ctx.config)) {
                return abandonPrompt(st, now, ctx, effects)
            }
        }

        // A prayer window that opens under a live hard surface outranks it: the surface is re-resolved
        // so that Stay is never withheld at prayer time (rule zero, §3.6). TIME_UP keeps its own
        // filtered options and a self-test is not a real interception, so both are left alone.
        if (st.phase == Phase.PROMPTING && ctx.window.kind == WindowKind.PRAYER) {
            val showing = st.prompt
            if (showing != null && showing.kind in HARD_SURFACES) {
                val upgraded = showing.copy(kind = SurfaceKind.PRAYER, shownWall = now.wallMs)
                st = st.copy(prompt = upgraded)
                effects += Effect.UpdateSurface(ui(st, upgraded, ctx, now, reason = null))
            }
        }

        return when (input) {
            is GuardInput.SelfTest -> selfTest(st, input, now)
            is GuardInput.Reconcile -> Accounting.chargeSpans(st, input.spans, ctx)
            is GuardInput.StartFocus -> startFocus(st, input, now, ctx, effects)
            is GuardInput.ForegroundChanged -> foreground(st, input, now, ctx, effects)
            is GuardInput.Chose -> chose(st, input.choice, now, ctx, effects)
            GuardInput.ScreenOff -> screenOff(st, now, ctx, effects)
            GuardInput.ScreenOn -> {
                val on = st.copy(screenOn = true)
                val pkg = on.foregroundPackage
                val guardedByConfig = pkg != null &&
                    Guardedness.isGuarded(pkg, null, AppSurface.APP, ctx.config)
                // G-07: an unlock may only resume a grant over an app the grant covers. A known,
                // unguarded foreground (WhatsApp, the launcher, the camera, Settings) leaves the
                // grant held, so the grace ends it as the early exit it was instead of draining the
                // budget there and naming that app on TIME_UP. An unknown foreground means no window
                // data arrived at all, and only then is the held grant trusted to resume.
                val sameWindow = on.grant?.windowAtStart == ctx.window.kind
                when {
                    // F-01: a lock/unlock during a Stay must not silently freeze the grant. The grace
                    // exists to bridge exactly this moment, so the screen coming back on resumes it.
                    on.phase == Phase.HELD && on.grant != null &&
                        (pkg == null || guardedByConfig) && sameWindow ->
                        resumeSavedGrant(on, on.grant, pkg ?: on.grant.firstPackage, now, effects)

                    pkg != null && on.phase in setOf(Phase.IDLE, Phase.HELD) && guardedByConfig ->
                        foreground(on, GuardInput.ForegroundChanged(pkg), now, ctx, effects)

                    else -> on
                }
            }
            GuardInput.Tick -> st
        }
    }

    /** Keeps the state's own invariants true even when an input sequence is nonsensical. */
    private fun settle(state: EngineState, now: TimeSnapshot, ctx: GuardContext): EngineState {
        var st = state
        if (st.phase == Phase.PROMPTING && st.prompt == null) {
            st = st.copy(phase = Phase.IDLE, foregroundGuarded = false)
        }
        if (st.phase != Phase.PROMPTING && st.prompt != null) {
            st = st.copy(prompt = null)
        }
        if (st.phase in GRANT_PHASES && st.grant == null) {
            st = st.copy(phase = Phase.IDLE, foregroundGuarded = false)
        }
        if (st.phase == Phase.IDLE && st.grant != null) {
            // A grant never survives outside a grant phase; dropping it here is what keeps a spent or
            // discarded grant from being resumed by a later guarded open.
            st = st.copy(grant = null)
        }
        val full = Accounting.fullBudgetMs(ctx.config.mode)
        if (st.budget.remainingMs > full) {
            st = st.copy(budget = st.budget.copy(remainingMs = full))
        }
        if (st.rest != null && st.rest.remainingMs <= 0L) {
            st = st.copy(rest = null)
        }
        return st.copy(lastSeenWall = now.wallMs, lastSeenElapsed = now.elapsedMs, bootCount = now.bootCount)
    }

    // ── foreground ─────────────────────────────────────────────────────────────────────────────

    private fun foreground(
        state: EngineState,
        input: GuardInput.ForegroundChanged,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val pkg = input.packageName ?: return state // an unknown window is not a decision
        // T19: Sadiq itself is transparent. The surface underneath must not flicker.
        if (pkg == Guardedness.OWN_PACKAGE) return state

        val testMatch = state.testArm?.packageName == pkg
        val guarded = (testMatch && state.phase == Phase.IDLE) ||
            Guardedness.isGuarded(pkg, input.domain, input.surface, ctx.config)

        // T35: a real guarded open fades any pending kindle silently. A self-test open must not,
        // because nothing was actually used.
        var st = state
        if (guarded && !testMatch && st.kindles.isNotEmpty()) {
            st = st.copy(kindles = emptyList())
        }

        return when (st.phase) {
            Phase.IDLE ->
                if (guarded) openFromIdle(st, pkg, input.domain, testMatch, now, ctx, effects)
                else st.copy(foregroundPackage = pkg, foregroundGuarded = false)

            Phase.PROMPTING -> promptForeground(st, pkg, input.domain, guarded, now, ctx, effects)

            Phase.GRANTED ->
                if (guarded) grantForeground(st, pkg, now, effects)
                else holdGrant(st, pkg, now, effects)

            Phase.HELD ->
                if (guarded) heldForeground(st, pkg, input.domain, now, ctx, effects)
                else st.copy(foregroundPackage = pkg, foregroundGuarded = false)

            Phase.PAUSED ->
                if (guarded) pausedForeground(st, pkg, input.domain, now, ctx, effects)
                else st.copy(foregroundPackage = pkg, foregroundGuarded = false)
        }
    }

    /** T01 / T33b, and the saved-grant case T01 carves out. */
    private fun openFromIdle(
        state: EngineState,
        pkg: String,
        domain: String?,
        testMatch: Boolean,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (testMatch) return enterTest(state, pkg, now, ctx, effects)
        val saved = state.grant
        if (saved != null && saved.consumedMs < saved.grantedMs) {
            return resumeSavedGrant(state, saved, pkg, now, effects)
        }

        val kind = resolver.resolve(
            ctx = ctx,
            nowWall = now.wallMs,
            focusRunning = state.focus != null,
            restActive = restActive(state),
            recentOpensWall = state.recentOpensWall,
            lastReflectWall = state.lastReflectWall,
        )
        val prompt = promptOf(kind, pkg, domain, now)
        val st = state.copy(
            grant = null,
            phase = Phase.PROMPTING,
            prompt = prompt,
            foregroundGuarded = true,
            foregroundPackage = pkg,
            recentOpensWall = state.recentOpensWall + now.wallMs,
            lastReflectWall = if (kind == SurfaceKind.REFLECT) now.wallMs else state.lastReflectWall,
            budget = state.budget.copy(lastGuardedUseElapsed = now.elapsedMs),
        )
        effects += Effect.DuckMedia
        effects += Effect.ShowSurface(ui(st, prompt, ctx, now, reason = null))
        effects += Effect.Record(openEvent(prompt, OpenOutcome.DISMISSED, now, ctx, withDecision = false))
        return st
    }

    /** T33b: the armed self-test shows the real surface for a package that need not be guarded. */
    private fun enterTest(
        state: EngineState,
        pkg: String,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val arm = state.testArm ?: return state
        val prompt = promptOf(
            kind = SurfaceKind.TEST,
            packageName = pkg,
            domain = null,
            now = now,
            testMode = true,
            returnRoute = arm.returnRoute,
        )
        val st = state.copy(
            phase = Phase.PROMPTING,
            prompt = prompt,
            testArm = null,
            foregroundGuarded = true,
            foregroundPackage = pkg,
        )
        effects += Effect.DuckMedia
        effects += Effect.ShowSurface(ui(st, prompt, ctx, now, reason = null))
        return st
    }

    private fun resumeSavedGrant(
        state: EngineState,
        saved: Grant,
        pkg: String,
        now: TimeSnapshot,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = saved.copy(
            heldSinceElapsed = null,
            pausedSinceElapsed = null,
            lastAccountedElapsed = now.elapsedMs,
        )
        effects += Effect.Live(
            LiveState.GrantRunning(pkg, grant.kind, grant.grantedMs, Accounting.grantRemainingMs(grant)),
        )
        return state.copy(
            phase = Phase.GRANTED,
            grant = grant,
            foregroundGuarded = true,
            foregroundPackage = pkg,
        )
    }

    private fun promptForeground(
        state: EngineState,
        pkg: String,
        domain: String?,
        guarded: Boolean,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val prompt = state.prompt ?: return state
        if (prompt.testMode) return testGestureAway(state, prompt, now, ctx, effects)
        if (!guarded) return leftByGesture(state, prompt, pkg, now, ctx, effects)

        // T18: another guarded app opened behind the surface; the kind stays, the chip changes.
        val updated = prompt.copy(packageName = pkg, domain = domain)
        val st = state.copy(prompt = updated, foregroundGuarded = true, foregroundPackage = pkg)
        effects += Effect.UpdateSurface(ui(st, updated, ctx, now, updated.reflectReason))
        return st
    }

    /** T07: he swiped away, went home, or tapped another app. */
    private fun leftByGesture(
        state: EngineState,
        prompt: Prompt,
        pkg: String,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        effects += Effect.DismissSurface
        effects += Effect.ReleaseMedia
        effects += Effect.Record(openEvent(prompt, OpenOutcome.LEFT_GESTURE, now, ctx))
        var st = state.copy(
            phase = Phase.IDLE,
            prompt = null,
            foregroundGuarded = false,
            foregroundPackage = pkg,
        )
        val grant = st.grant
        if (grant != null) st = closeGrant(st, GrantEnd.DISCARDED, now, ctx, effects)
        return applyLeave(st, prompt.kind, LeaveKind.LEFT_GESTURE, now, ctx, grant, prompt.packageName, effects)
    }

    /** A self-test that loses the foreground ends the same way it would from Leave, without a star. */
    private fun testGestureAway(
        state: EngineState,
        prompt: Prompt,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        effects += Effect.DismissSurface
        effects += Effect.ReleaseMedia
        val route = prompt.returnRoute
        effects += if (route != null) Effect.OpenSadiq(route) else Effect.GoHome
        return state.copy(phase = Phase.IDLE, prompt = null, foregroundGuarded = false)
    }

    /** T22: one grant covers every guarded app; only the label changes. */
    private fun grantForeground(
        state: EngineState,
        pkg: String,
        now: TimeSnapshot,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        effects += Effect.Live(
            LiveState.GrantRunning(pkg, grant.kind, grant.grantedMs, Accounting.grantRemainingMs(grant)),
        )
        return state.copy(foregroundGuarded = true, foregroundPackage = pkg)
    }

    /** T23: he left or the screen went off mid-grant; the three-minute grace starts. */
    private fun holdGrant(
        state: EngineState,
        pkg: String?,
        now: TimeSnapshot,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        val held = grant.copy(heldSinceElapsed = now.elapsedMs)
        effects += Effect.Live(
            LiveState.GrantHeld(Accounting.grantRemainingMs(held), explicitPause = false),
        )
        return state.copy(
            phase = Phase.HELD,
            grant = held,
            foregroundGuarded = false,
            foregroundPackage = pkg,
        )
    }

    /** T02 / T03. */
    private fun heldForeground(
        state: EngineState,
        pkg: String,
        domain: String?,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        val heldSince = grant.heldSinceElapsed
        val insideGrace = heldSince != null && now.elapsedMs - heldSince < EngineRules.HOLD_GRACE_MS
        val sameWindow = grant.windowAtStart == ctx.window.kind
        if (insideGrace && sameWindow) {
            val resumed = grant.copy(heldSinceElapsed = null, lastAccountedElapsed = now.elapsedMs)
            effects += Effect.Live(
                LiveState.GrantRunning(pkg, resumed.kind, resumed.grantedMs, Accounting.grantRemainingMs(resumed)),
            )
            return state.copy(
                phase = Phase.GRANTED,
                grant = resumed,
                foregroundGuarded = true,
                foregroundPackage = pkg,
            )
        }
        return reopenAfterGrace(state, pkg, domain, now, ctx, effects)
    }

    /** T03: the grace is gone, so the grant closes and the normal question is asked again. */
    private fun reopenAfterGrace(
        state: EngineState,
        pkg: String,
        domain: String?,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        var st = closeGrant(state, GrantEnd.LEFT, now, ctx, effects)
        val kind = resolver.resolve(
            ctx = ctx,
            nowWall = now.wallMs,
            focusRunning = st.focus != null,
            restActive = restActive(st),
            recentOpensWall = st.recentOpensWall,
            lastReflectWall = st.lastReflectWall,
        )
        val prompt = promptOf(kind, pkg, domain, now)
        st = st.copy(
            phase = Phase.PROMPTING,
            prompt = prompt,
            foregroundGuarded = true,
            foregroundPackage = pkg,
            lastReflectWall = if (kind == SurfaceKind.REFLECT) now.wallMs else st.lastReflectWall,
        )
        effects += Effect.DuckMedia
        effects += Effect.ShowSurface(ui(st, prompt, ctx, now, reason = null))
        return st
    }

    /** T04 / T05. */
    private fun pausedForeground(
        state: EngineState,
        pkg: String,
        domain: String?,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val kind = resolver.resolve(
            ctx = ctx,
            nowWall = now.wallMs,
            focusRunning = state.focus != null,
            restActive = restActive(state),
            recentOpensWall = state.recentOpensWall,
            lastReflectWall = state.lastReflectWall,
        )
        val grant = state.grant
        val soft = kind == SurfaceKind.PAUSE || kind == SurfaceKind.REFLECT
        if (grant != null && soft) return resumePrompt(state, pkg, domain, now, ctx, effects)

        var st = state
        if (grant != null) st = closeGrant(st, GrantEnd.SUPERSEDED, now, ctx, effects)
        val prompt = promptOf(kind, pkg, domain, now)
        st = st.copy(
            phase = Phase.PROMPTING,
            prompt = prompt,
            foregroundGuarded = true,
            foregroundPackage = pkg,
            lastReflectWall = if (kind == SurfaceKind.REFLECT) now.wallMs else st.lastReflectWall,
        )
        effects += Effect.ShowSurface(ui(st, prompt, ctx, now, reason = null))
        return st
    }

    /** T04: the saved time is offered back instead of the question. */
    private fun resumePrompt(
        state: EngineState,
        pkg: String,
        domain: String?,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val prompt = promptOf(SurfaceKind.PAUSE, pkg, domain, now)
        val st = state.copy(phase = Phase.PROMPTING, prompt = prompt, foregroundGuarded = true, foregroundPackage = pkg)
        effects += Effect.DuckMedia
        effects += Effect.ShowSurface(ui(st, prompt, ctx, now, reason = null))
        return st
    }

    // ── screen ────────────────────────────────────────────────────────────────────────────────

    private fun screenOff(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState = when (state.phase) {
        Phase.PROMPTING -> {
            // T08: screen off while prompting is a leave, and counts as one.
            val prompt = state.prompt
            if (prompt == null) {
                state.copy(screenOn = false, foregroundGuarded = false)
            } else if (prompt.testMode) {
                testGestureAway(state, prompt, now, ctx, effects).copy(screenOn = false)
            } else {
                effects += Effect.DismissSurface
                effects += Effect.ReleaseMedia
                effects += Effect.Record(openEvent(prompt, OpenOutcome.SCREEN_OFF, now, ctx))
                var st = state.copy(
                    phase = Phase.IDLE,
                    prompt = null,
                    screenOn = false,
                    foregroundGuarded = false,
                )
                val grant = st.grant
                if (grant != null) st = closeGrant(st, GrantEnd.DISCARDED, now, ctx, effects)
                applyLeave(st, prompt.kind, LeaveKind.SCREEN_OFF, now, ctx, grant, prompt.packageName, effects)
            }
        }
        Phase.GRANTED -> holdGrant(state, state.foregroundPackage, now, effects).copy(
            screenOn = false,
            foregroundGuarded = false,
        )
        else -> state.copy(screenOn = false, foregroundGuarded = false)
    }

    // ── choices ───────────────────────────────────────────────────────────────────────────────

    private fun chose(
        state: EngineState,
        choice: Choice,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState = when (state.phase) {
        Phase.PROMPTING -> {
            val prompt = state.prompt
            if (prompt == null) state else choosePrompt(state, prompt, choice, now, ctx, effects)
        }
        Phase.PAUSED -> when (choice) {
            is Choice.DiscardPaused -> expirePause(state, GrantEnd.DISCARDED, now, ctx, effects)
            is Choice.Resume -> resumeFromPause(state, now, effects)
            else -> state
        }
        Phase.IDLE, Phase.GRANTED, Phase.HELD -> when (choice) {
            is Choice.EndFocus -> {
                val focus = state.focus
                if (focus == null) state else endFocus(state, focus, now, ctx, effects)
            }
            else -> state
        }
    }

    private fun choosePrompt(
        state: EngineState,
        prompt: Prompt,
        choice: Choice,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState = when (choice) {
        is Choice.Leave -> leavePrompt(state, prompt, now, ctx, effects)
        is Choice.Stay -> stayWithinBudget(state, prompt, choice.minutes, now, ctx, effects)
        is Choice.Resume -> resume(state, prompt, now, ctx, effects)
        is Choice.FreeScroll -> freeScroll(state, prompt, choice.minutes, now, ctx, effects)
        is Choice.QuickNeed -> quickNeed(state, prompt, now, ctx, effects)
        is Choice.StartFocusNow -> startFocusNow(state, prompt, choice.minutes, now, ctx, effects)
        is Choice.WindDown -> windDown(state, prompt, now, ctx, effects)
        is Choice.BackToFocus -> backToFocus(state, prompt, effects)
        is Choice.EndFocus -> endFocusFromPrompt(state, prompt, now, ctx, effects)
        is Choice.Reflect -> reflect(state, prompt, choice.reason, now, ctx, effects)
        is Choice.DoneEarly, is Choice.PauseGrant, is Choice.DiscardPaused -> state
        // The override contract only (OV-0): arming and opening are T36/T37, which OV-1 builds here.
        // Until then the two inputs are accepted and change nothing — no arm, no grant, no record.
        is Choice.ArmOverride, is Choice.Override -> state
    }

    /** T06: Leave, or Back. */
    private fun leavePrompt(
        state: EngineState,
        prompt: Prompt,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        effects += Effect.DismissSurface
        val route = prompt.returnRoute
        effects += if (prompt.testMode && route != null) Effect.OpenSadiq(route) else Effect.GoHome
        effects += Effect.ReleaseMedia
        effects += Effect.Record(openEvent(prompt, OpenOutcome.LEFT, now, ctx))
        var st = state.copy(
            phase = Phase.IDLE,
            prompt = null,
            foregroundGuarded = false,
            foregroundPackage = null,
        )
        val grant = st.grant
        // A paused grant cannot outlive the prompt that offered it back: leaving discards it.
        // A spent grant was already recorded as EXPIRED when TIME_UP appeared, so it is only dropped.
        st = if (grant != null && grant.pausedSinceElapsed != null) {
            closeGrant(st, GrantEnd.DISCARDED, now, ctx, effects)
        } else {
            st.copy(grant = null)
        }
        // T33b: the self-test makes no economy call; onboarding records the welcome star (E9).
        if (prompt.testMode) return st
        // Leaving a TIME_UP after a Stay is a kept word (E2), which is why the spent grant is passed.
        val leave = if (prompt.kind == SurfaceKind.TIME_UP && grant != null) {
            LeaveKind.KEPT_WORD
        } else {
            LeaveKind.LEFT_PROMPT
        }
        return applyLeave(st, prompt.kind, leave, now, ctx, grant, prompt.packageName, effects)
    }

    /** T09: Stay. */
    private fun stayWithinBudget(
        state: EngineState,
        prompt: Prompt,
        minutes: Int,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val options = InterceptUiBuilder.stayOptions(prompt.kind, state, ctx)
        if (minutes !in options) return state
        val restActive = restActive(state)
        val kind = if (restActive) GrantKind.PRAYER_STAY else GrantKind.STAY
        // A Stay taken inside a prayer window is a prayer Stay in length even when no rest is running:
        // the chips on screen are the prayer chips, and a grant longer than the cap they promise would
        // let fifteen minutes run through Maghrib (A4-04, §3.6 rule zero).
        val inPrayerWindow = ctx.window.kind == WindowKind.PRAYER
        val capped = if (kind == GrantKind.PRAYER_STAY || inPrayerWindow) {
            minOf(minutes, EngineRules.PRAYER_STAY_MAX_MIN)
        } else {
            minutes
        }
        return startGrant(state, prompt, kind, capped * EngineRules.MINUTE_MS, OpenOutcome.STAYED, now, ctx, effects)
    }

    /** T10: Resume, from the prompt that offered the saved time back. */
    private fun resume(
        state: EngineState,
        prompt: Prompt,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        if (grant.pausedSinceElapsed == null) return state
        val resumed = grant.copy(
            pausedSinceElapsed = null,
            heldSinceElapsed = null,
            lastAccountedElapsed = now.elapsedMs,
        )
        val st = state.copy(
            phase = Phase.GRANTED,
            prompt = null,
            grant = resumed,
            foregroundGuarded = true,
            foregroundPackage = prompt.packageName,
        )
        effects += Effect.DismissSurface
        effects += Effect.ReleaseMedia
        effects += Effect.Live(
            LiveState.GrantRunning(prompt.packageName, resumed.kind, resumed.grantedMs, Accounting.grantRemainingMs(resumed)),
        )
        effects += Effect.Record(openEvent(prompt, OpenOutcome.RESUMED, now, ctx, grantId = resumed.id))
        return st
    }

    /** T11: free scroll. */
    private fun freeScroll(
        state: EngineState,
        prompt: Prompt,
        minutes: Int,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (minutes <= 0) return state
        if (!resolver.allowsFreeScroll(prompt.kind, ctx, state.focus != null)) return state
        val available = economy.freeScrollAvailableMs(state.economy)
        val wanted = minutes * EngineRules.MINUTE_MS
        if (wanted > available) return state
        return startGrant(state, prompt, GrantKind.FREE, wanted, OpenOutcome.FREE_SCROLL, now, ctx, effects)
    }

    /** T12: the one-minute quick need. */
    private fun quickNeed(
        state: EngineState,
        prompt: Prompt,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val left = economy.quickNeedsLeft(state.economy)
        if (!SurfaceResolver.allowsQuickNeed(prompt.kind, restActive(state), left)) return state
        return startGrant(
            state = state,
            prompt = prompt,
            kind = GrantKind.QUICK,
            grantedMs = EngineRules.QUICK_NEED_MS,
            outcome = OpenOutcome.QUICK_NEED,
            now = now,
            ctx = ctx,
            effects = effects,
        )
    }

    /** T13: the morning's one thing, or an answer to the reflection, becomes a focus session. */
    private fun startFocusNow(
        state: EngineState,
        prompt: Prompt,
        minutes: Int,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (prompt.kind != SurfaceKind.MORNING && prompt.kind != SurfaceKind.REFLECT) return state
        val planned = minutes.coerceIn(1, MAX_FOCUS_MIN) * EngineRules.MINUTE_MS
        val focus = FocusState(
            id = "f${state.seq + 1}",
            plannedMs = planned,
            elapsedMs = 0L,
            lastAccountedElapsed = now.elapsedMs,
            startedWall = now.wallMs,
            sound = SoundKind.NONE,
        )
        val st = state.copy(
            phase = Phase.IDLE,
            prompt = null,
            focus = focus,
            seq = state.seq + 1,
            foregroundGuarded = false,
            foregroundPackage = null,
        )
        effects += Effect.DismissSurface
        effects += Effect.GoHome
        effects += Effect.ReleaseMedia
        effects += Effect.Live(LiveState.FocusRunning(planned, planned, SoundKind.NONE))
        effects += Effect.Record(openEvent(prompt, OpenOutcome.FOCUS_STARTED, now, ctx))
        return st
    }

    /** T14: wind down with rain, straight from the sleep gate. */
    private fun windDown(
        state: EngineState,
        prompt: Prompt,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (prompt.kind != SurfaceKind.SLEEP_GATE) return state
        effects += Effect.DismissSurface
        effects += Effect.GoHome
        effects += Effect.PlaySound(SoundKind.RAIN, EngineRules.WIND_DOWN_MS)
        effects += Effect.Record(openEvent(prompt, OpenOutcome.WIND_DOWN, now, ctx))
        return state.copy(
            phase = Phase.IDLE,
            prompt = null,
            foregroundGuarded = false,
            foregroundPackage = null,
        )
    }

    /** T15: back to the focus that is paying for the session. No leave-star. */
    private fun backToFocus(
        state: EngineState,
        prompt: Prompt,
        effects: MutableList<Effect>,
    ): EngineState {
        if (prompt.kind != SurfaceKind.FOCUS_GUARD) return state
        effects += Effect.DismissSurface
        effects += Effect.GoHome
        effects += Effect.ReleaseMedia
        return state.copy(
            phase = Phase.IDLE,
            prompt = null,
            foregroundGuarded = false,
            foregroundPackage = null,
        )
    }

    /** T16: ending the focus does not unlock anything; it asks the normal question. */
    private fun endFocusFromPrompt(
        state: EngineState,
        prompt: Prompt,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (prompt.kind != SurfaceKind.FOCUS_GUARD) return state
        val focus = state.focus ?: return state
        var st = closeFocus(state, focus, completed = false, now = now, ctx = ctx, effects = effects)
        // The surface is about to be replaced by the ordinary question, so the shade has to stop
        // counting down the session the user just ended; the next prompt is not a timer.
        effects += Effect.Live(liveAfterFocus(st))
        val kind = resolver.withoutFocus(
            ctx = ctx,
            nowWall = now.wallMs,
            restActive = restActive(st),
            recentOpensWall = st.recentOpensWall,
            lastReflectWall = st.lastReflectWall,
        )
        val next = promptOf(kind, prompt.packageName, prompt.domain, now)
        st = st.copy(phase = Phase.PROMPTING, prompt = next, foregroundGuarded = true)
        effects += Effect.UpdateSurface(ui(st, next, ctx, now, reason = null))
        return st
    }

    /** T17: he tapped a reflection chip. */
    private fun reflect(
        state: EngineState,
        prompt: Prompt,
        reason: ReflectionReason,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (prompt.kind != SurfaceKind.REFLECT) return state
        val opens = reflection.opensInWindow(state.recentOpensWall, now.wallMs)
        val updated = prompt.copy(reflectReason = reason, shownWall = now.wallMs)
        val st = state.copy(prompt = updated, lastReflectWall = now.wallMs)
        effects += Effect.Record(
            RecordEvent.Reflected(now.wallMs, ctx.logicalDay, reason, updated.packageName, opens),
        )
        effects += Effect.UpdateSurface(ui(st, updated, ctx, now, reason = reason))
        return st
    }

    // ── grant lifetime ────────────────────────────────────────────────────────────────────────

    /** T21: the grant is spent; the surface comes back as TIME_UP. */
    private fun timeUp(
        state: EngineState,
        grant: Grant,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val pkg = state.foregroundPackage ?: grant.firstPackage
        val prompt = promptOf(SurfaceKind.TIME_UP, pkg, null, now)
        // The spent grant stays in the state until the surface is answered: it is what tells TIME_UP
        // which kind ran out, and it is what lets a Leave from TIME_UP prove E2 (KEPT_WORD) to the
        // economy. It is closed for the record now and dropped quietly when the surface goes.
        var st = state.copy(phase = Phase.PROMPTING, prompt = prompt, foregroundGuarded = true)
        effects += Effect.DuckMedia
        effects += Effect.ShowSurface(ui(st, prompt, ctx, now, reason = null))
        effects += Effect.Live(LiveState.None)
        effects += Effect.Record(RecordEvent.GrantClosed(grant, now.wallMs, GrantEnd.EXPIRED, ctx.logicalDay))
        // A spent Stay budget starts the rest before the surface is answered.
        if (grant.kind == GrantKind.STAY && Accounting.isBudgetExhausted(st.budget)) {
            st = Accounting.startRest(st, now, ctx)
        }
        return st
    }

    /** T24: "I'm done" from the notification, in front or held. */
    private fun doneEarly(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        if (state.phase == Phase.GRANTED && state.screenOn && state.foregroundGuarded) {
            effects += Effect.GoHome
        }
        effects += Effect.Live(LiveState.None)
        var st = closeGrant(state, GrantEnd.DONE_EARLY, now, ctx, effects)
        st = st.copy(phase = Phase.IDLE, prompt = null, foregroundGuarded = false, foregroundPackage = null)
        return applyLeave(st, null, LeaveKind.DONE_EARLY, now, ctx, grant, grant.firstPackage, effects)
    }

    /** T25: Pause, only ever for a Stay grant. */
    private fun pauseGrant(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        if (grant.kind != GrantKind.STAY) return state
        if (state.phase == Phase.GRANTED && state.screenOn && state.foregroundGuarded) {
            effects += Effect.GoHome
        }
        val paused = grant.copy(
            pausedSinceElapsed = now.elapsedMs,
            heldSinceElapsed = null,
            lastAccountedElapsed = now.elapsedMs,
        )
        effects += Effect.Live(
            LiveState.GrantHeld(Accounting.grantRemainingMs(paused), explicitPause = true),
        )
        return state.copy(
            phase = Phase.PAUSED,
            grant = paused,
            foregroundGuarded = false,
            foregroundPackage = null,
        )
    }

    /** T26: the grace ran out. The three minutes away were the proof, so the star lights at once. */
    private fun heldGraceOver(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        effects += Effect.Live(LiveState.None)
        var st = closeGrant(state, GrantEnd.LEFT, now, ctx, effects)
        st = st.copy(phase = Phase.IDLE, foregroundGuarded = false, foregroundPackage = null)
        return applyLeave(st, null, LeaveKind.DONE_EARLY, now, ctx, grant, grant.firstPackage, effects)
    }

    /** T27: sixty minutes are up, or he discarded the pause. */
    private fun expirePause(
        state: EngineState,
        end: GrantEnd,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (state.grant == null) return state
        effects += Effect.Live(LiveState.None)
        var st = closeGrant(state, end, now, ctx, effects)
        st = st.copy(phase = Phase.IDLE, foregroundGuarded = false, foregroundPackage = null)
        return st
    }

    private fun resumeFromPause(
        state: EngineState,
        now: TimeSnapshot,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        if (grant.pausedSinceElapsed == null) return state
        val resumed = grant.copy(pausedSinceElapsed = null, lastAccountedElapsed = now.elapsedMs)
        effects += Effect.Live(
            LiveState.GrantRunning(
                state.foregroundPackage ?: grant.firstPackage,
                resumed.kind,
                resumed.grantedMs,
                Accounting.grantRemainingMs(resumed),
            ),
        )
        return state.copy(phase = Phase.GRANTED, grant = resumed, foregroundGuarded = true)
    }

    // ── focus ─────────────────────────────────────────────────────────────────────────────────

    /** T28: a focus started from the Focus tab. */
    private fun startFocus(
        state: EngineState,
        input: GuardInput.StartFocus,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        if (state.focus != null) return state
        // The surface owns the screen; T13 is the in-surface path to a focus.
        if (state.phase == Phase.PROMPTING) return state
        var st = state
        if (st.grant != null) st = closeGrant(st, GrantEnd.SUPERSEDED, now, ctx, effects)
        val planned = input.plannedMin.coerceIn(1, MAX_FOCUS_MIN) * EngineRules.MINUTE_MS
        val focus = FocusState(
            id = "f${st.seq + 1}",
            plannedMs = planned,
            elapsedMs = 0L,
            lastAccountedElapsed = now.elapsedMs,
            startedWall = now.wallMs,
            sound = input.sound,
        )
        st = st.copy(
            phase = Phase.IDLE,
            prompt = null,
            focus = focus,
            seq = st.seq + 1,
            foregroundGuarded = false,
        )
        effects += Effect.Live(LiveState.FocusRunning(planned, planned, input.sound))
        if (input.sound != SoundKind.NONE) effects += Effect.PlaySound(input.sound, null)
        return st
    }

    private fun endFocus(
        state: EngineState,
        focus: FocusState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val st = closeFocus(state, focus, completed = false, now = now, ctx = ctx, effects = effects)
        // Ending a focus does not end a grant taken on FOCUS_GUARD and still running underneath it, so
        // the shade must keep that grant's countdown instead of going quiet (the completion path uses
        // the same helper for the same reason).
        effects += Effect.Live(liveAfterFocus(st))
        return st
    }

    private fun closeFocus(
        state: EngineState,
        focus: FocusState,
        completed: Boolean,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val (day, awards) = economy.onFocusEnded(
            day = state.economy,
            focus = focus,
            completed = completed,
            nowWall = now.wallMs,
            logicalDay = ctx.logicalDay,
        )
        effects += Effect.Record(
            RecordEvent.FocusClosed(focus, now.wallMs, completed, awards.size, ctx.logicalDay),
        )
        for (award in awards) effects += Effect.Record(RecordEvent.StarLit(award))
        if (focus.sound != SoundKind.NONE) effects += Effect.StopSound
        return state.copy(focus = null, economy = day)
    }

    /**
     * What notification id 1 should show once a focus ends.
     *
     * Ending a focus does not end a grant that happens to be running underneath it, and the shade can
     * only describe one of them. Re-deriving the live state from the state the reducer just produced
     * keeps the notification honest in both cases: a running grant gets its own countdown back,
     * anything else falls back to the keep-alive, which is the post that replaces a stale timer.
     *
     * @param st the state after the focus was cleared.
     */
    private fun liveAfterFocus(st: EngineState): LiveState {
        val g = st.grant
        return if (st.phase == Phase.GRANTED && g != null) {
            LiveState.GrantRunning(st.foregroundPackage ?: g.firstPackage, g.kind, g.grantedMs, Accounting.grantRemainingMs(g))
        } else {
            LiveState.None
        }
    }

    // ── self-test ─────────────────────────────────────────────────────────────────────────────

    /** T33a: arm the self-test. The caller launches the package itself, so the real path runs. */
    private fun selfTest(state: EngineState, input: GuardInput.SelfTest, now: TimeSnapshot): EngineState {
        if (state.phase != Phase.IDLE) return state
        return state.copy(
            testArm = TestArm(
                packageName = input.packageName,
                returnRoute = input.returnRoute,
                armedElapsed = now.elapsedMs,
            ),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private fun promptOf(
        kind: SurfaceKind,
        packageName: String,
        domain: String?,
        now: TimeSnapshot,
        testMode: Boolean = false,
        returnRoute: String? = null,
        reason: ReflectionReason? = null,
    ): Prompt = Prompt(
        kind = kind,
        packageName = packageName,
        // Only the matched host is kept, never the URL (§3.9).
        domain = Guardedness.normalizeDomain(domain),
        shownWall = now.wallMs,
        reflectReason = reason,
        testMode = testMode,
        returnRoute = returnRoute,
    )

    private fun ui(
        state: EngineState,
        prompt: Prompt,
        ctx: GuardContext,
        now: TimeSnapshot,
        reason: ReflectionReason?,
    ): InterceptUi {
        val facts = UiFacts(
            freeScrollAvailableMs = economy.freeScrollAvailableMs(state.economy),
            quickNeedsLeft = economy.quickNeedsLeft(state.economy),
            reflectionOpens = reflection.opensInWindow(state.recentOpensWall, now.wallMs),
            leaveEarnsStar = economy.peekLeave(
                day = state.economy,
                window = ctx.window.kind,
                nowWall = now.wallMs,
                pendingKindles = state.kindles.size,
            ),
        )
        val suggestion: Suggestion = suggester.suggest(
            InterceptUiBuilder.suggestionContext(state, ctx, now, reason),
        )
        return InterceptUiBuilder.build(
            state = state,
            prompt = prompt,
            ctx = ctx,
            now = now,
            facts = facts,
            suggestion = suggestion,
        )
    }

    private fun openEvent(
        prompt: Prompt,
        outcome: OpenOutcome,
        now: TimeSnapshot,
        ctx: GuardContext,
        withDecision: Boolean = true,
        grantId: String? = null,
    ): RecordEvent.Open = RecordEvent.Open(
        at = now.wallMs,
        logicalDay = ctx.logicalDay,
        packageName = prompt.packageName,
        domain = prompt.domain,
        surface = prompt.kind,
        outcome = outcome,
        decisionMs = if (withDecision) (now.wallMs - prompt.shownWall).coerceAtLeast(0L) else null,
        grantId = grantId,
    )

    private fun closeGrant(
        state: EngineState,
        end: GrantEnd,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = state.grant ?: return state
        effects += Effect.Record(RecordEvent.GrantClosed(grant, now.wallMs, end, ctx.logicalDay))
        return state.copy(grant = null)
    }

    /**
     * Hands a leave to the economy and starts the kindle it earns.
     *
     * @param surface the surface the leave came from, or null when no surface was involved (a grant
     *   control from the notification).
     */
    private fun applyLeave(
        state: EngineState,
        surface: SurfaceKind?,
        leave: LeaveKind,
        now: TimeSnapshot,
        ctx: GuardContext,
        grant: Grant?,
        sourcePackage: String?,
        effects: MutableList<Effect>,
    ): EngineState {
        // E1 excludes FOCUS_GUARD: the focus session is what pays for that time (T15), which is why the
        // surface never says "light a star". Back, a gesture and the screen going off all reach the
        // economy through here, so gating on the surface the prompt was built for is what keeps that
        // promise on every exit path — the star the button offers and the star the economy pays are one
        // rule (A4-09). The economy cannot see a surface kind; the caller has to.
        if (surface == SurfaceKind.FOCUS_GUARD) return state
        val (day, decision) = economy.onLeave(
            day = state.economy,
            leave = leave,
            window = ctx.window.kind,
            nowWall = now.wallMs,
            pendingKindles = state.kindles.size,
            grant = grant,
        )
        var st = state.copy(economy = day)
        when (decision) {
            is LeaveDecision.Kindle -> {
                // Only one kindle may be pending; a new one replaces whatever was waiting.
                st = st.copy(
                    kindles = listOf(
                        Kindle(
                            id = "k${st.seq + 1}",
                            leave = leave,
                            createdElapsed = now.elapsedMs,
                            confirmAtElapsed = now.elapsedMs + decision.confirmAfterMs,
                            sourcePackage = sourcePackage,
                            window = ctx.window.kind,
                        ),
                    ),
                    seq = st.seq + 1,
                )
            }
            is LeaveDecision.LitNow -> effects += Effect.Record(RecordEvent.StarLit(decision.award))
            is LeaveDecision.None -> Unit
        }
        return st
    }

    private fun startGrant(
        state: EngineState,
        prompt: Prompt,
        kind: GrantKind,
        grantedMs: Long,
        outcome: OpenOutcome,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        val grant = Grant(
            id = "g${state.seq + 1}",
            kind = kind,
            grantedMs = grantedMs,
            consumedMs = 0L,
            startedWall = now.wallMs,
            lastAccountedElapsed = now.elapsedMs,
            heldSinceElapsed = null,
            pausedSinceElapsed = null,
            windowAtStart = ctx.window.kind,
            firstPackage = prompt.packageName,
        )
        var st = state.copy(
            phase = Phase.GRANTED,
            prompt = null,
            grant = grant,
            seq = state.seq + 1,
            foregroundGuarded = true,
            foregroundPackage = prompt.packageName,
            economy = spendOnGrant(state.economy, kind, grantedMs),
        )
        st = Accounting.countProtectedUse(
            state = st,
            ctx = ctx,
            grants = if (kind == GrantKind.QUICK) 0 else 1,
            quickNeeds = if (kind == GrantKind.QUICK) 1 else 0,
        )
        effects += Effect.DismissSurface
        effects += Effect.ReleaseMedia
        effects += Effect.Live(
            LiveState.GrantRunning(prompt.packageName, kind, grantedMs, grantedMs),
        )
        effects += Effect.Record(openEvent(prompt, outcome, now, ctx, grantId = grant.id))
        return st
    }

    /**
     * Free minutes are reserved when the grant is handed out, so leaving early never refunds them and
     * a quick need is counted the moment he takes it (§3.8 caps).
     */
    private fun spendOnGrant(day: EconomyDay, kind: GrantKind, grantedMs: Long): EconomyDay = when (kind) {
        GrantKind.FREE -> day.copy(freeUsedMs = day.freeUsedMs + grantedMs)
        GrantKind.QUICK -> day.copy(quickNeedsUsed = day.quickNeedsUsed + 1)
        GrantKind.STAY, GrantKind.PRAYER_STAY -> day
        // "Open anyway" drains nothing: not the budget, not free scroll, not the quick minutes (A.9).
        GrantKind.OVERRIDE -> day
    }

    /** T20: the app is no longer guarded, so the surface goes away without a decision or a star. */
    private fun abandonPrompt(
        state: EngineState,
        now: TimeSnapshot,
        ctx: GuardContext,
        effects: MutableList<Effect>,
    ): EngineState {
        effects += Effect.DismissSurface
        effects += Effect.ReleaseMedia
        var st = state.copy(phase = Phase.IDLE, prompt = null, foregroundGuarded = false)
        val grant = st.grant
        st = if (grant != null && grant.pausedSinceElapsed != null) {
            closeGrant(st, GrantEnd.DISCARDED, now, ctx, effects)
        } else {
            st.copy(grant = null)
        }
        return st
    }

    private fun restActive(state: EngineState): Boolean = (state.rest?.remainingMs ?: 0L) > 0L

    private companion object {
        /** Upper bound for a focus session a surface may start; the chips sit well below it. */
        const val MAX_FOCUS_MIN: Int = 180

        val GRANT_PHASES: Set<Phase> = setOf(Phase.GRANTED, Phase.HELD)

        /** Surfaces a prayer window may never leave on screen (§3.6 rule zero). */
        val HARD_SURFACES: Set<SurfaceKind> = setOf(
            SurfaceKind.COOLDOWN,
            SurfaceKind.SLEEP_GATE,
            SurfaceKind.FOCUS_GUARD,
            SurfaceKind.MORNING,
        )
    }
}
