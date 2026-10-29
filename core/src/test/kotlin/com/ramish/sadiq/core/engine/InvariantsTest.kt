package com.ramish.sadiq.core.engine

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

        private fun randomChoice(): Choice = when (rng.nextInt(14)) {
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
                // Stars and records that fell due on this same input are housekeeping (T30/T34), not a
                // reaction to the foreground change.
                // Stars, a completed focus and a stopping sound can fall due on any input; they are
                // housekeeping (T29/T30/T34), not a reaction to the foreground change.
                val due = setOf("Record(StarLit)", "StopSound")
                val reacting = transition.effects.filter { effect ->
                    val tag = effect.tag()
                    !tag.startsWith("Record(") &&
                        !tag.startsWith("FocusCompleted(") &&
                        tag !in due
                }
                if (reacting.isNotEmpty()) {
                    fail("our own package produced effects: ${reacting.map { it.tag() }}")
                }
                if (transition.state.phase != before.phase || transition.state.prompt != before.prompt) {
                    fail("our own package changed the surface")
                }
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
                if (ui.kind == SurfaceKind.PRAYER && ui.stayOptionsMin.isEmpty()) {
                    fail("a PRAYER surface without Stay at $where")
                }
            }
        }

        private companion object {
            val GRANT_PHASES = setOf(Phase.GRANTED, Phase.HELD, Phase.PAUSED)
        }
    }
}
