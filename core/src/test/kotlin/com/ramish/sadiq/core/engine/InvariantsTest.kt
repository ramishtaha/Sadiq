package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.DefaultStarEconomy
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.testing.FakeReflectionPolicy
import com.ramish.sadiq.core.testing.FakeStarEconomy
import com.ramish.sadiq.core.testing.FakeSuggester
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.testing.TestTime
import com.ramish.sadiq.core.time.TimeSnapshot
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ten thousand random input sequences with a fixed seed, against the invariant list of WP-06.
 *
 * This is the test that finds the sequence nobody imagined: a reboot during a paused grant while a
 * kindle is pending and the logical day rolls. It is deterministic by construction — one seed, no
 * clock, no randomness beyond [Random] — and it checks rule zero from the outside, by insisting that
 * no surface outlives the state that shows it and that every Leave actually leaves.
 *
 * The fakes answer generously (every leave makes a kindle, every kindle and focus pays a star), so the
 * engine runs with stars and balances moving rather than sitting still.
 */
class InvariantsTest {

    private val config: GuardConfig = Fixtures.config(
        apps = listOf(Fixtures.INSTAGRAM, Fixtures.YOUTUBE),
        domains = setOf("instagram.com"),
        webGuardEnabled = true,
    )

    @Test
    fun tenThousandRandomInputsKeepEveryInvariant() {
        val run = Run(seed = 20_261_029L, config = config)
        run.run(10_000)
        assertTrue(run.surfacesShown > 0, "the walk must actually intercept something")
        assertTrue(run.leavesTaken > 0, "the walk must actually leave something")
    }

    @Test
    fun theSameSeedProducesTheSameStatesAndEffects() {
        val first = Run(seed = 7L, config = config)
        val second = Run(seed = 7L, config = config)
        first.run(500)
        second.run(500)

        assertEquals(first.state, second.state)
        assertEquals(first.lastTags, second.lastTags)
    }

    /**
     * A4-04, as a property rather than a story: whatever the engine is holding when the adhan lands —
     * a grant that just ran out, a budget at zero, a rest, a focus — every surface built inside the
     * prayer window still offers Stay, and none of them offers more than the prayer cap.
     *
     * This is the check the audit asked for, and it is why `TIME_UP` cannot be the one surface that
     * withholds Stay at Asr: a Stay that ran out there is answered by another capped Stay, not by
     * "take me home" alone. The kinds that never offer a Stay at all are deliberately absent — the
     * resolver upgrades them to PRAYER before they can be shown in the window, so an empty list there
     * is not a prayer-window surface.
     */
    @Test
    fun A4_04_everyStayBuiltInAPrayerWindowIsOfferedAndCapped() {
        val now = Fixtures.EVENING
        val ctx = Fixtures.context(
            now = now,
            window = Fixtures.window(kind = WindowKind.PRAYER, prayer = PrayerName.MAGHRIB),
        )
        val facts = UiFacts(
            freeScrollAvailableMs = 10 * MIN,
            quickNeedsLeft = 3,
            reflectionOpens = 0,
            leaveEarnsStar = true,
        )
        val expiredGrants = listOf<GrantKind?>(
            null,
            GrantKind.STAY,
            GrantKind.FREE,
            GrantKind.QUICK,
            GrantKind.PRAYER_STAY,
        )
        val budgets = listOf(10 * MIN, 5 * MIN, 0L)

        for (kind in STAY_SURFACES) {
            for (expired in expiredGrants) {
                for (budget in budgets) {
                    for (rest in listOf(null, Fixtures.rest())) {
                        for (focus in listOf(null, Fixtures.focus())) {
                            val prompt = Fixtures.prompt(kind = kind)
                            val state = Fixtures.state(
                                now = now,
                                phase = Phase.PROMPTING,
                                prompt = prompt,
                                grant = expired?.let {
                                    Fixtures.grant(kind = it, grantedMs = MIN, consumedMs = MIN)
                                },
                                budget = Fixtures.budget(remainingMs = budget),
                                rest = rest,
                                focus = focus,
                            )
                            val ui = InterceptUiBuilder.build(state, prompt, ctx, now, facts, suggestion = null)
                            val where = "$kind expired=$expired budget=$budget rest=${rest != null} " +
                                "focus=${focus != null}"
                            if (ui.stayOptionsMin.isEmpty()) {
                                fail("a prayer-window surface withholds Stay at $where")
                            }
                            val over = ui.stayOptionsMin.filter { it > EngineRules.PRAYER_STAY_MAX_MIN }
                            if (over.isNotEmpty()) {
                                fail("$where offers $over min past the prayer cap")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A4-09 revisited for the door: whatever the engine is holding, a surface only ever offers
      * "Open anyway" where §A.1 says it may (§A.1, §A.9).
      *
      * The property is enforced on every surface the walk above builds, in `checkOverrideOffer`, so
      * this test's job is to prove the walk actually reached the door — a rule that never fires proves
      * nothing. A violation fails the walk itself, with the step and the state in the message.
      */
     @Test
     fun INV_override_never_offered_in_prayer_window_or_focus_or_with_stay_or_free_chips() {
         val run = Run(seed = 20_261_029L, config = config)
         run.run(10_000)
         assertTrue(run.overrideOffers > 0, "the walk must actually reach the door")
     }

     /**
      * No star can be traced back to an "Open anyway" grant (§A.3, E2/E3/E7).
      *
      * The three doors that light a star elsewhere — "I'm done" mid-grant, the HELD grace running out,
      * and leaving a Time's up — are each run on the real economy from a live OVERRIDE grant. None of
      * them may pay, not at the moment it happens and not three minutes later through a kindle.
      */
     @Test
     fun INV_no_star_award_references_an_override_grant() {
         for (exit in OverrideExit.entries) {
             val (grantId, effects) = exitAnOverrideGrant(exit)
             val awards = effects.filterIsInstance<Effect.Record>()
                 .map { it.event }
                 .filterIsInstance<RecordEvent.StarLit>()
                 .map { it.award }
             assertTrue(
                 awards.none { it.sourceRef == grantId },
                 "$exit lit a star on the override grant $grantId: ${awards.map { it.kind }}",
             )
             assertTrue(awards.isEmpty(), "$exit after an override lit ${awards.map { it.kind }}")
         }
     }

     /**
      * Backing out of the confirm view is the surface's own Leave, not a bonus (F-01 shape, A.3).
      *
      * Same state, same instant, two rigs: one leaves straight away, the other arms first. The arm is
      * discarded with the prompt, so the two end in the same state — same phase, same grant, same
      * kindle with the same timestamps, same economy — and the economy was asked the same question.
      */
     @Test
     fun INV_arm_then_leave_equals_leave() {
         val now = Fixtures.EVENING
         val armed = cooldownRig(now)
         val plain = Rig(economy = FakeStarEconomy(freeScrollMs = 0L), now = now, ctx = armed.ctx, initial = armed.state)

         plain.choose(Choice.Leave, now)
         armed.choose(Choice.ArmOverride, now)
         armed.choose(Choice.Leave, now)

         assertEquals(plain.state, armed.state)
         assertEquals(plain.tags(), armed.tags())
         assertEquals("LEFT_PROMPT", armed.economy.lastLeave?.name)
         assertEquals(plain.economy.lastLeave, armed.economy.lastLeave)
         assertEquals(plain.economy.lastPendingKindles, armed.economy.lastPendingKindles)
         assertEquals(1, armed.state.kindles.size)
     }

     /**
      * Two doors a day and one a night, charged when the grant starts (§A.3).
      *
      * The night cap is the reason the day cap alone is not enough: 04:00 falls inside Bedtime, so a
      * door used at 03:50 and a fresh day counter at 04:10 would otherwise be two doors in one night.
      * The ledger row is keyed by the gate's own start, so it outlives the rollover that empties the
      * day.
      */
     @Test
     fun INV_caps() {
         // ── the day: two, then the caption names 04:00 ────────────────────────────────────────
         val rig = cooldownRig(TestTime.at("2026-10-29T20:00"))
         rig.openAnyway()
         assertEquals(1, rig.state.economy.overridesUsed)

         rig.tick(TestTime.at("2026-10-29T20:11"))
         assertEquals(SurfaceKind.TIME_UP, rig.state.prompt?.kind)
         rig.choose(Choice.Leave)
         rig.openGuarded(at = TestTime.at("2026-10-29T20:12"))
         assertEquals(SurfaceKind.COOLDOWN, rig.state.prompt?.kind)
         assertEquals(1, rig.ui().overrideLeft)

         rig.openAnyway()
         assertEquals(2, rig.state.economy.overridesUsed)

         rig.tick(TestTime.at("2026-10-29T20:23"))
         assertEquals(SurfaceKind.TIME_UP, rig.state.prompt?.kind)
         rig.choose(Choice.Leave)
         rig.openGuarded(at = TestTime.at("2026-10-29T20:24"))
         assertEquals(SurfaceKind.COOLDOWN, rig.state.prompt?.kind)
         assertNull(rig.ui().overrideLeft, "the third door of a logical day must not exist")
         assertEquals(
             TestTime.at("2026-10-30T04:00").wallMs,
             rig.ui().overrideBackAtWall,
             "a spent day cap is back at the next 04:00",
         )
         rig.choose(Choice.ArmOverride, TestTime.at("2026-10-29T20:24"))
         assertTrue(rig.effects.isEmpty())
         assertEquals(2, rig.state.economy.overridesUsed)

         // ── the night: one, kept across the 04:00 rollover inside the same gate ───────────────
         val gateStart = TestTime.at("2026-10-29T22:30").wallMs
         val gateEnd = TestTime.at("2026-10-30T05:21").wallMs
         fun gateAt(at: TimeSnapshot, day: Int): GuardContext = Fixtures.context(
             now = at,
             window = Fixtures.window(kind = WindowKind.SLEEP_GATE, startWall = gateStart, endWall = gateEnd),
             logicalDay = day,
             today = Fixtures.prayerDay(
                 epochDay = day,
                 fajr = TestTime.at("2026-10-30T05:21").wallMs,
                 nextFajr = TestTime.at("2026-10-31T05:22").wallMs,
             ),
         )

         val before = TestTime.at("2026-10-30T03:50")
         val night = Rig(now = before, ctx = gateAt(before, Fixtures.EPOCH_DAY))
         night.openGuarded(context = gateAt(before, Fixtures.EPOCH_DAY))
         assertEquals(EngineRules.OVERRIDE_NIGHT_CAP, night.ui().overrideLeft)
         night.openAnyway()
         assertEquals(1, night.state.ledger.single { it.kind == WindowKind.SLEEP_GATE }.overrides)
         assertEquals(1, night.state.economy.overridesUsed)

         // 04:01 — the grant is spent after the day has rolled; he leaves the Time's up and comes back.
         val after = TestTime.at("2026-10-30T04:10")
         val afterCtx = gateAt(after, Fixtures.EPOCH_DAY + 1)
         night.tick(TestTime.at("2026-10-30T04:01"), context = afterCtx)
         assertEquals(SurfaceKind.TIME_UP, night.state.prompt?.kind)
         assertEquals(0, night.state.economy.overridesUsed, "the day counter rolled at 04:00")
         night.choose(Choice.Leave, TestTime.at("2026-10-30T04:01"), context = afterCtx)

         night.openGuarded(at = after, context = afterCtx)
         assertEquals(SurfaceKind.SLEEP_GATE, night.state.prompt?.kind)
         assertNull(night.ui().overrideLeft, "the night cap did not roll with the day")
         assertEquals(
             TestTime.at("2026-10-30T05:21").wallMs,
             night.ui().overrideBackAtWall,
             "a spent night cap is back at Fajr",
         )
         assertEquals(1, night.state.ledger.single { it.kind == WindowKind.SLEEP_GATE }.overrides)
         night.choose(Choice.ArmOverride, after, context = afterCtx)
         assertTrue(night.effects.isEmpty())
         assertEquals(0, night.state.economy.overridesUsed)
     }

     /** The three "I'm done" doors an override must not pay for. */
     private enum class OverrideExit { DONE_EARLY, HELD_TIMEOUT, TIME_UP_LEAVE }

     /**
      * Opens the door on the real economy and then leaves the ten minutes by [exit].
      *
      * @return the grant id and every effect of the walk, so a star can be looked for where it lands.
      */
     private fun exitAnOverrideGrant(exit: OverrideExit): Pair<String, List<Effect>> {
         val now = Fixtures.EVENING
         val ctx = Fixtures.context(now = now)
         val engine = GuardEngine(DefaultStarEconomy(), FakeReflectionPolicy(), FakeSuggester())
         val resting = Fixtures.state(
             now = now,
             rest = Fixtures.rest(remainingMs = 120 * MIN, lastAccountedElapsed = now.elapsedMs),
             budget = Fixtures.budget(remainingMs = 0L, lastGuardedUseElapsed = now.elapsedMs),
         )
         val effects = mutableListOf<Effect>()
         var state = resting

         fun step(input: GuardInput, at: TimeSnapshot, context: GuardContext = ctx) {
             val transition = engine.reduce(state, input, at, context)
             state = transition.state
             effects += transition.effects
         }

         step(GuardInput.ForegroundChanged(Fixtures.INSTAGRAM.packageName), now)
         step(GuardInput.Chose(Choice.ArmOverride), now)
         val opened = now.afterMs(EngineRules.OVERRIDE_WAIT_MS)
         step(GuardInput.Chose(Choice.Override(ReflectionReason.NEED)), opened)
         val grant = state.grant
         assertNotNull(grant)
         assertEquals(GrantKind.OVERRIDE, grant.kind)

         val midGrant = opened.afterMs(MIN)
         when (exit) {
             OverrideExit.DONE_EARLY -> {
                 step(GuardInput.Chose(Choice.DoneEarly), midGrant)
                 assertEquals(Phase.IDLE, state.phase)
             }
             OverrideExit.HELD_TIMEOUT -> {
                 step(GuardInput.ScreenOff, midGrant)
                 assertEquals(Phase.HELD, state.phase)
                 step(GuardInput.Tick, midGrant.afterMs(EngineRules.HOLD_GRACE_MS))
                 assertEquals(Phase.IDLE, state.phase)
             }
             OverrideExit.TIME_UP_LEAVE -> {
                 step(GuardInput.Tick, opened.afterMs(EngineRules.OVERRIDE_MS))
                 assertEquals(SurfaceKind.TIME_UP, state.prompt?.kind)
                 step(GuardInput.Chose(Choice.Leave), opened.afterMs(EngineRules.OVERRIDE_MS))
                 assertEquals(Phase.IDLE, state.phase)
             }
         }
         // Three quiet minutes plus the grace: a kindle nobody expected must not light late either.
         step(GuardInput.Tick, opened.afterMs(EngineRules.OVERRIDE_MS + EngineRules.HOLD_GRACE_MS + 5 * MIN))
         assertEquals(0, state.economy.litLeave)
         assertEquals(0, state.economy.litTotal)
         assertTrue(state.kindles.isEmpty())
         return grant.id to effects
     }

     /**
     * One deterministic walk through the state machine.
     *
     * @property seed the only source of variation.
     * @property config the settings the whole walk shares, including a web-guarded domain.
     */
    private class Run(private val seed: Long, private val config: GuardConfig) {

        private val rng = Random(seed)
        private val economy = FakeStarEconomy(
            kindleAward = Fixtures.star(),
            focusAwards = listOf(Fixtures.star()),
            protectedAwards = listOf(Fixtures.star()),
            freeScrollMs = 15 * MIN,
            quickNeeds = 3,
        )
        private val reflection = FakeReflectionPolicy()
        private val suggester = FakeSuggester()
        private val engine = GuardEngine(economy, reflection, suggester)

        var now: TimeSnapshot = Fixtures.EVENING
        var ctx: GuardContext = Fixtures.context(now = now, config = config)
        var state: EngineState = engine.initial(now, ctx)
        var lastTags: List<String> = emptyList()
        var surfacesShown: Int = 0
        var leavesTaken: Int = 0

        /** How many surfaces offered the door: the property below is worthless if the walk never sees one. */
        var overrideOffers: Int = 0

        private var surfaceOnScreen: Boolean = false

        fun run(steps: Int) {
            repeat(steps) { step ->
                advanceClock()
                ctx = buildContext()
                val input = nextInput()
                val before = state
                val transition = engine.reduce(state, input, now, ctx)
                state = transition.state
                lastTags = transition.effects.map { it.tag() }

                trackSurface(transition.effects)
                checkRuleZero(before, input, transition)
                checkInvariants(step, input, before, transition)
            }
        }

        private fun advanceClock() {
            val roll = rng.nextInt(100)
            now = when {
                roll == 0 -> TestTime.reboot(now, wallMinutes = (rng.nextInt(120) + 1).toLong())
                roll < 8 -> TestTime.after(now, (rng.nextInt(240) + 1).toLong())
                roll < 40 -> now
                else -> TestTime.after(now, rng.nextInt(8).toLong())
            }
        }

        private fun buildContext(): GuardContext {
            val day = if (rng.nextInt(20) == 0) Fixtures.EPOCH_DAY + 1 else Fixtures.EPOCH_DAY
            val ended = if (rng.nextInt(12) == 0) listOf(Fixtures.span()) else emptyList()
            reflection.reflect = rng.nextInt(5) == 0
            return Fixtures.context(
                now = now,
                config = config,
                window = randomWindow(),
                logicalDay = day,
                endedProtected = ended,
            )
        }

        private fun randomWindow(): ActiveWindow = when (rng.nextInt(5)) {
            0 -> Fixtures.window(
                kind = WindowKind.PRAYER,
                startWall = now.wallMs - 5 * MIN,
                endWall = now.wallMs + 10 * MIN,
                prayer = PrayerName.MAGHRIB,
            )
            1 -> Fixtures.window(
                kind = WindowKind.SLEEP_GATE,
                startWall = now.wallMs - 60 * MIN,
                endWall = now.wallMs + 60 * MIN,
            )
            2 -> Fixtures.window(
                kind = WindowKind.MORNING,
                startWall = now.wallMs - 10 * MIN,
                endWall = now.wallMs + 20 * MIN,
            )
            else -> Fixtures.noWindow()
        }

        private fun nextInput(): GuardInput {
            val packages = listOf(
                Fixtures.INSTAGRAM.packageName,
                Fixtures.YOUTUBE.packageName,
                Guardedness.OWN_PACKAGE,
                "com.android.dialer",
                "com.example.news",
            )
            return when (rng.nextInt(100)) {
                in 0..19 -> GuardInput.ForegroundChanged(
                    packageName = packages[rng.nextInt(packages.size)],
                    domain = if (rng.nextBoolean()) "instagram.com" else null,
                    surface = AppSurface.entries[rng.nextInt(AppSurface.entries.size)],
                )
                in 20..29 -> GuardInput.ScreenOff
                in 30..36 -> GuardInput.ScreenOn
                in 37..49 -> GuardInput.Tick
                in 50..79 -> GuardInput.Chose(randomChoice())
                in 80..86 -> GuardInput.StartFocus(
                    plannedMin = listOf(5, 15, 25, 45)[rng.nextInt(4)],
                    sound = SoundKind.entries[rng.nextInt(SoundKind.entries.size)],
                )
                in 87..92 -> GuardInput.Reconcile(
                    listOf(
                        Fixtures.usageSpan(
                            packageName = packages[rng.nextInt(packages.size)],
                            startWall = now.wallMs - (rng.nextInt(40) + 1).toLong() * MIN,
                            endWall = now.wallMs,
                        ),
                    ),
                )
                else -> GuardInput.SelfTest("com.example.news", "health")
            }
        }

        private fun randomChoice(): Choice = when (rng.nextInt(16)) {
            0 -> Choice.Leave
            1 -> Choice.Stay(listOf(2, 5, 10, 15, 25)[rng.nextInt(5)])
            2 -> Choice.Resume
            3 -> Choice.FreeScroll(listOf(5, 10, 15)[rng.nextInt(3)])
            4 -> Choice.QuickNeed
            5 -> Choice.DoneEarly
            6 -> Choice.PauseGrant
            7 -> Choice.DiscardPaused
            8 -> Choice.Reflect(ReflectionReason.entries[rng.nextInt(ReflectionReason.entries.size)])
            9 -> Choice.StartFocusNow(listOf(5, 25)[rng.nextInt(2)])
            10 -> Choice.WindDown
            11 -> Choice.BackToFocus
            12 -> Choice.EndFocus
            13 -> Choice.ArmOverride
            14 -> Choice.Override(ReflectionReason.entries[rng.nextInt(ReflectionReason.entries.size)])
            else -> Choice.Leave
        }

        private fun trackSurface(effects: List<Effect>) {
            for (effect in effects) {
                when (effect) {
                    is Effect.ShowSurface -> {
                        surfaceOnScreen = true
                        surfacesShown += 1
                    }
                    is Effect.DismissSurface -> surfaceOnScreen = false
                    else -> Unit
                }
            }
        }

        /** Rule zero: a Leave always leaves, and no surface outlives the phase that shows it. */
        private fun checkRuleZero(before: EngineState, input: GuardInput, transition: Transition) {
            val tags = transition.effects.map { it.tag() }

            if (before.phase == Phase.PROMPTING) {
                val leaves = input is GuardInput.Chose && input.choice is Choice.Leave
                val screenOff = input === GuardInput.ScreenOff
                val unguarded = input is GuardInput.ForegroundChanged &&
                    input.packageName != null &&
                    input.packageName != Guardedness.OWN_PACKAGE &&
                    !Guardedness.isGuarded(input.packageName, input.domain, input.surface, ctx.config)
                if (leaves || screenOff || unguarded) {
                    if (!tags.contains("Dismiss")) {
                        fail("a surface was left without a DismissSurface: input=$input tags=$tags")
                    }
                }
                if (leaves) {
                    leavesTaken += 1
                    if (transition.state.phase == Phase.PROMPTING) {
                        fail("Leave left the engine prompting")
                    }
                }
            }

            if (input is GuardInput.ForegroundChanged && input.packageName == Guardedness.OWN_PACKAGE) {
                // T19 is transparent: our own package never shows, dismisses, holds, ducks or goes home.
                // Stars, a completed focus and a stopping sound can fall due on any input; they are
                // housekeeping (T29/T30/T34), not a reaction to the foreground change. A completed focus
                // also resets notification id 1 and rewrites a FOCUS_GUARD that was still on screen, and
                // both of those belong to the closed session for the same reason — they are only
                // forgiven here when this transition actually closed one.
                val closedFocus = transition.effects.any { effect ->
                    effect is Effect.Record && effect.event is RecordEvent.FocusClosed
                }
                val due = setOf("Record(StarLit)", "StopSound")
                val reacting = transition.effects.filter { effect ->
                    val tag = effect.tag()
                    !tag.startsWith("Record(") &&
                        !tag.startsWith("FocusCompleted(") &&
                        !(closedFocus && (effect is Effect.Live || effect is Effect.UpdateSurface)) &&
                        tag !in due
                }
                if (reacting.isNotEmpty()) {
                    fail("our own package produced effects: ${reacting.map { it.tag() }}")
                }
                val surfaceChanged = transition.state.phase != before.phase ||
                    transition.state.prompt != before.prompt
                // A closing session rewrites the FOCUS_GUARD it ran out under in place — same phase,
                // new prompt — and that is the closed session's housekeeping, not a reaction to our
                // own package. Any phase change, or a prompt change with no session closing, is T19
                // broken.
                val rewrittenByClosedFocus = closedFocus &&
                    before.phase == Phase.PROMPTING &&
                    transition.state.phase == Phase.PROMPTING
                if (surfaceChanged && !rewrittenByClosedFocus) fail("our own package changed the surface")
            }

            if (transition.state.phase != Phase.PROMPTING && surfaceOnScreen) {
                fail("phase ${transition.state.phase} with a surface still on screen: $tags")
            }
        }

        private fun checkInvariants(step: Int, input: GuardInput, before: EngineState, transition: Transition) {
            val s = state
            val where = "step $step input=$input before=${before.phase}/${before.prompt?.kind} " +
                "phase=${s.phase} grant=${s.grant} prompt=${s.prompt}"

            if (s.phase == Phase.PROMPTING && s.prompt == null) fail("PROMPTING without a prompt at $where")
            if (s.phase != Phase.PROMPTING && s.prompt != null) fail("a prompt outside PROMPTING at $where")
            if (s.phase in GRANT_PHASES && s.grant == null) fail("${s.phase} without a grant at $where")
            if (s.phase == Phase.PAUSED && s.grant?.pausedSinceElapsed == null) {
                fail("PAUSED without a pause anchor at $where")
            }
            // At most one grant exists by construction (a single nullable field); the assertion keeps
            // that guarantee visible if the state ever grows a grant list.
            if (listOfNotNull(s.grant).size > 1) fail("more than one grant at $where")
            if (s.kindles.size > 1) fail("more than one kindle pending at $where")

            val full = Accounting.fullBudgetMs(config.mode)
            if (s.budget.remainingMs !in 0..full) fail("budget ${s.budget.remainingMs} outside 0..$full at $where")
            if (s.rest != null && s.rest.remainingMs < 0L) fail("negative rest at $where")

            // Both caps are charged at the moment a grant starts, so no walk can overrun them (§A.3).
            if (s.economy.overridesUsed > EngineRules.OVERRIDE_DAY_CAP) {
                fail("${s.economy.overridesUsed} overrides in logical day ${s.economy.logicalDay} at $where")
            }
            for (row in s.ledger) {
                if (row.overrides > EngineRules.OVERRIDE_NIGHT_CAP) {
                    fail("${row.overrides} overrides in the ${row.kind} window ${row.startWall} at $where")
                }
            }

            // "A PRAYER-window open never yields COOLDOWN, SLEEP_GATE, FOCUS_GUARD or MORNING": every
            // resolution of a guarded open inside a prayer window must come out soft.
            val guardedOpen = input is GuardInput.ForegroundChanged &&
                input.packageName != null &&
                input.packageName != Guardedness.OWN_PACKAGE &&
                Guardedness.isGuarded(input.packageName, input.domain, input.surface, ctx.config)
            if (ctx.window.kind == WindowKind.PRAYER && guardedOpen && s.prompt != null) {
                val forbidden = setOf(
                    SurfaceKind.COOLDOWN,
                    SurfaceKind.SLEEP_GATE,
                    SurfaceKind.FOCUS_GUARD,
                    SurfaceKind.MORNING,
                )
                if (s.prompt.kind in forbidden) {
                    fail("a prayer-window open yielded ${s.prompt.kind} at $where")
                }
            }

            for (effect in transition.effects) {
                val ui = when (effect) {
                    is Effect.ShowSurface -> effect.ui
                    is Effect.UpdateSurface -> effect.ui
                    else -> null
                } ?: continue
                // Rule zero in a prayer window, for every surface the engine builds there: Stay is
                // never withheld, never capped above the prayer cap, no matter what the engine was
                // holding when the adhan landed (A4-04).
                if (ctx.window.kind == WindowKind.PRAYER && ui.kind in InvariantsTest.STAY_SURFACES) {
                    if (ui.stayOptionsMin.isEmpty()) {
                        fail("a prayer-window ${ui.kind} without Stay at $where")
                    }
                    val over = ui.stayOptionsMin.filter { it > EngineRules.PRAYER_STAY_MAX_MIN }
                    if (over.isNotEmpty()) {
                        fail("a prayer-window ${ui.kind} offering $over min at $where")
                    }
                }
                checkOverrideOffer(ui, where)
            }
        }

        /**
         * §A.1 as a property: wherever the door is offered, the surface also offers no yes of its own,
         * no free scroll, no focus and no prayer window — and the day and night caps have room.
         *
         * Every field is read from the surface itself, because the UI is what he can press; a rule
         * the engine keeps privately but forgets to withhold would still be a door on screen.
         */
        private fun checkOverrideOffer(ui: InterceptUi, where: String) {
            if (ui.overrideArmedElapsed != null && ui.overrideLeft == null) {
                fail("a confirm view with no door behind it on ${ui.kind} at $where")
            }
            if (ui.overrideBackAtWall != null && ui.overrideLeft != null) {
                fail("a back-at caption beside a door that is offered on ${ui.kind} at $where")
            }
            if (ui.overrideLeft == null) return
            overrideOffers += 1

            if (ui.kind !in InvariantsTest.OVERRIDE_KINDS) fail("the door on ${ui.kind} at $where")
            if (ui.testMode) fail("the door in the self-test at $where")
            if (ctx.window.kind == WindowKind.PRAYER) fail("the door at prayer time at $where")
            if (ui.focusRemainingMs != null) fail("the door during Focus at $where")
            if (ui.stayOptionsMin.isNotEmpty()) {
                fail("the door beside Stay chips ${ui.stayOptionsMin} at $where")
            }
            if (ui.freeScrollOptionsMin.isNotEmpty()) {
                fail("the door beside free scroll chips ${ui.freeScrollOptionsMin} at $where")
            }
            if (ui.overrideWaitMs == null) fail("a door with no stated wait at $where")
            if ((ui.overrideLeft ?: 0) <= 0) fail("a door with no uses left at $where")
        }

        private companion object {
            val GRANT_PHASES = setOf(Phase.GRANTED, Phase.HELD, Phase.PAUSED)
        }
    }

    private companion object {
        /**
         * The surfaces that offer a Stay at all. The hard ones never do, and the resolver upgrades
         * them to PRAYER before a prayer window can show them, so they are outside the property.
         */
        val STAY_SURFACES: List<SurfaceKind> = listOf(
            SurfaceKind.PRAYER,
            SurfaceKind.MORNING,
            SurfaceKind.PAUSE,
            SurfaceKind.REFLECT,
            SurfaceKind.TIME_UP,
        )

        /** The only surfaces that may ever show the door (§A.1). */
        val OVERRIDE_KINDS: Set<SurfaceKind> = setOf(
            SurfaceKind.COOLDOWN,
            SurfaceKind.SLEEP_GATE,
            SurfaceKind.TIME_UP,
        )
    }
}
