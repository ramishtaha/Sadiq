package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.testing.FakeReflectionPolicy
import com.ramish.sadiq.core.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The surface order of §3.6, first match wins, plus the option rules that hang off each surface.
 *
 * The resolver is tested through its own API (no reducer), so a broken order is localised to the
 * order and not to the state machine around it.
 */
class SurfaceResolverTest {

    private val reflection = FakeReflectionPolicy()
    private val resolver = SurfaceResolver(reflection)
    private val now = Fixtures.EVENING
    private val nowWall = now.wallMs

    private fun resolve(
        ctx: GuardContext,
        focus: Boolean = false,
        rest: Boolean = false,
        opens: List<Long> = emptyList(),
        lastReflect: Long? = null,
    ): SurfaceKind = resolver.resolve(ctx, nowWall, focus, rest, opens, lastReflect)

    // ── order ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun prayerWindowWinsOverEverything() {
        val ctx = Fixtures.context(
            now = now,
            window = Fixtures.window(WindowKind.PRAYER, prayer = PrayerName.MAGHRIB),
        )
        assertEquals(SurfaceKind.PRAYER, resolve(ctx, focus = true, rest = true))
    }

    @Test
    fun focusBeatsSleepGateRestMorningAndReflection() {
        reflection.reflect = true
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        assertEquals(SurfaceKind.FOCUS_GUARD, resolve(ctx, focus = true, rest = true))

        val morning = Fixtures.context(now = now, window = Fixtures.window(WindowKind.MORNING))
        assertEquals(SurfaceKind.FOCUS_GUARD, resolve(morning, focus = true))
    }

    @Test
    fun sleepGateBeatsRestMorningAndReflection() {
        reflection.reflect = true
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        assertEquals(SurfaceKind.SLEEP_GATE, resolve(ctx, rest = true))
    }

    @Test
    fun restBeatsMorningAndReflection() {
        reflection.reflect = true
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.MORNING))
        assertEquals(SurfaceKind.COOLDOWN, resolve(ctx, rest = true))
    }

    @Test
    fun morningBeatsReflection() {
        reflection.reflect = true
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.MORNING))
        assertEquals(SurfaceKind.MORNING, resolve(ctx))
    }

    @Test
    fun reflectionAppearsWhenTheRuleAsksForIt() {
        reflection.reflect = true
        val ctx = Fixtures.context(now = now)
        assertEquals(SurfaceKind.REFLECT, resolve(ctx, opens = listOf(nowWall - 60_000)))
        assertTrue(reflection.lastEnabled == true)
    }

    @Test
    fun reflectionIsSkippedWhenTheUserTurnedItOff() {
        reflection.reflect = true
        val ctx = Fixtures.context(now = now, config = Fixtures.config(reflectionEnabled = false))
        assertEquals(SurfaceKind.PAUSE, resolve(ctx))
    }

    @Test
    fun nothingElseMeansPause() {
        assertEquals(SurfaceKind.PAUSE, resolve(Fixtures.context(now = now)))
    }

    @Test
    fun withoutFocusFallsThroughToTheNextRule() {
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.SLEEP_GATE))
        assertEquals(SurfaceKind.SLEEP_GATE, resolver.withoutFocus(ctx, nowWall, restActive = false))
    }

    // ── options ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun freeScrollIsWithheldInEveryProtectedWindow() {
        assertTrue(resolver.allowsFreeScroll(SurfaceKind.PAUSE, Fixtures.context(now = now), false))
        for (kind in listOf(WindowKind.PRAYER, WindowKind.SLEEP_GATE, WindowKind.MORNING)) {
            val ctx = Fixtures.context(now = now, window = Fixtures.window(kind))
            assertFalse(
                resolver.allowsFreeScroll(SurfaceKind.PAUSE, ctx, false),
                "free scroll must be withheld in $kind",
            )
        }
        assertFalse(
            resolver.allowsFreeScroll(SurfaceKind.PAUSE, Fixtures.context(now = now), focusRunning = true),
        )
    }

    @Test
    fun freeScrollIsOnlyOfferedFromTheSoftSurfaces() {
        val ctx = Fixtures.context(now = now)
        for (kind in listOf(SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.COOLDOWN, SurfaceKind.TIME_UP)) {
            assertTrue(resolver.allowsFreeScroll(kind, ctx, false), "$kind should offer free scroll")
        }
        for (kind in listOf(SurfaceKind.PRAYER, SurfaceKind.SLEEP_GATE, SurfaceKind.MORNING, SurfaceKind.FOCUS_GUARD, SurfaceKind.TEST)) {
            assertFalse(resolver.allowsFreeScroll(kind, ctx, false), "$kind must not offer free scroll")
        }
    }

    @Test
    fun quickNeedNeedsAMinuteLeftAndTheRightSurface() {
        val ctx = Fixtures.context(now = now)
        assertFalse(SurfaceResolver.allowsQuickNeed(SurfaceKind.COOLDOWN, restActive = false, quickNeedsLeft = 0))
        for (kind in listOf(SurfaceKind.COOLDOWN, SurfaceKind.SLEEP_GATE, SurfaceKind.FOCUS_GUARD)) {
            assertTrue(SurfaceResolver.allowsQuickNeed(kind, restActive = false, quickNeedsLeft = 1))
        }
        assertTrue(SurfaceResolver.allowsQuickNeed(SurfaceKind.TIME_UP, restActive = true, quickNeedsLeft = 1))
        assertFalse(SurfaceResolver.allowsQuickNeed(SurfaceKind.TIME_UP, restActive = false, quickNeedsLeft = 1))
        assertFalse(SurfaceResolver.allowsQuickNeed(SurfaceKind.PAUSE, restActive = true, quickNeedsLeft = 1))
        assertFalse(SurfaceResolver.allowsQuickNeed(SurfaceKind.PRAYER, restActive = true, quickNeedsLeft = 1))
        assertFalse(SurfaceResolver.protectedWindowActive(ctx, false))
    }

    @Test
    fun prayerAlwaysOffersStayWithinTheCap() {
        val state = Fixtures.state(
            now = now,
            phase = Phase.PROMPTING,
            prompt = Fixtures.prompt(kind = SurfaceKind.PRAYER),
            budget = Fixtures.budget(remainingMs = 0L),
        )
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.PRAYER))
        val chips = InterceptUiBuilder.stayOptions(SurfaceKind.PRAYER, state, ctx)

        assertTrue(chips.isNotEmpty(), "rule zero: a prayer surface never withholds Stay")
        assertTrue(chips.all { it <= EngineRules.PRAYER_STAY_MAX_MIN })
    }

    @Test
    fun morningOffersTheHeldTwoMinutes() {
        val state = Fixtures.state(now = now, phase = Phase.PROMPTING, prompt = Fixtures.prompt(kind = SurfaceKind.MORNING))
        val ctx = Fixtures.context(now = now, window = Fixtures.window(WindowKind.MORNING))
        assertEquals(listOf(EngineRules.MORNING_STAY_MIN), InterceptUiBuilder.stayOptions(SurfaceKind.MORNING, state, ctx))
    }

    @Test
    fun hardSurfacesOfferNoStayAtAll() {
        val state = Fixtures.state(now = now, phase = Phase.PROMPTING, prompt = Fixtures.prompt())
        val ctx = Fixtures.context(now = now)
        for (kind in listOf(SurfaceKind.SLEEP_GATE, SurfaceKind.FOCUS_GUARD, SurfaceKind.COOLDOWN, SurfaceKind.TEST)) {
            assertTrue(
                InterceptUiBuilder.stayOptions(kind, state, ctx).isEmpty(),
                "$kind must not offer a Stay chip",
            )
        }
    }

    @Test
    fun stayChipsAreFilteredByTheBudgetAndCappedAtFour() {
        val ctx = Fixtures.context(now = now)
        fun chips(remaining: Long): List<Int> {
            val state = Fixtures.state(now = now, phase = Phase.PROMPTING, budget = Fixtures.budget(remainingMs = remaining))
            return InterceptUiBuilder.stayOptions(SurfaceKind.PAUSE, state, ctx)
        }
        assertEquals(listOf(2), chips(2 * MIN))
        assertEquals(listOf(2, 3), chips(3 * MIN))
        assertEquals(listOf(2, 5, 7), chips(7 * MIN))
        assertEquals(listOf(2, 5, 10, 15), chips(20 * MIN))
        assertTrue(chips(0L).isEmpty())
    }

    @Test
    fun freeChipsAreFilteredByTodaysBalance() {
        val ctx = Fixtures.context(now = now)
        val state = Fixtures.state(now = now, phase = Phase.PROMPTING)
        fun chips(availableMs: Long): List<Int> = InterceptUiBuilder.freeScrollOptions(
            SurfaceKind.PAUSE,
            state,
            ctx,
            UiFacts(freeScrollAvailableMs = availableMs, quickNeedsLeft = 3, reflectionOpens = 0, leaveEarnsStar = true),
        )
        assertEquals(listOf(5, 10), chips(10 * MIN))
        assertEquals(listOf(2), chips(2 * MIN))
        assertTrue(chips(0L).isEmpty())
        assertTrue(chips(10 * MIN).none { it > 10 })
    }

    @Test
    fun timeUpOnlyOffersStayAfterAStay() {
        val ctx = Fixtures.context(now = now)
        fun chips(kind: GrantKind?): List<Int> {
            val state = Fixtures.state(
                now = now,
                phase = Phase.PROMPTING,
                prompt = Fixtures.prompt(kind = SurfaceKind.TIME_UP),
                grant = Fixtures.grant(kind = kind ?: GrantKind.STAY, grantedMs = MIN, consumedMs = MIN),
            )
            return InterceptUiBuilder.stayOptions(SurfaceKind.TIME_UP, state, ctx)
        }
        assertTrue(chips(GrantKind.STAY).isNotEmpty())
        assertTrue(chips(GrantKind.FREE).isEmpty())
        assertTrue(chips(GrantKind.QUICK).isEmpty())
    }
}
