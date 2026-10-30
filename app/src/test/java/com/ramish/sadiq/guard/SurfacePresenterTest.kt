package com.ramish.sadiq.guard

import com.ramish.sadiq.contract.MediaDucker
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The 600 ms race of §3.6: the activity gets one window to prove it is on screen, and if it misses
 * it the overlay takes over and the fallback is counted for the day.
 *
 * Everything here runs on virtual time, which is the only way to assert "not yet at 599 ms, yes at
 * 600" without a flaky sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfacePresenterTest {

    @Test
    fun aMissedWindowNeverRequestsAnOverlayThatCannotDraw() = runTest {
        val rig = Rig(backgroundScope)

        rig.presenter.show(testUi())
        advanceTimeBy(599)
        runCurrent()
        assertFalse(rig.holder.overlayRequested.value, "the activity still has a millisecond")

        advanceTimeBy(2)
        runCurrent()

        // The fallback host is off (AUDIT-4 A4-01: its first frame crashed the process). A request
        // with nothing to draw it would leave the surface stuck and count a fallback that never
        // happened, and it is what makes the pop-ups card lie. The activity stays the only host.
        assertFalse(rig.holder.overlayRequested.value, "no host may be asked for while the host is off")
        assertTrue(rig.settings.overlayFallbacks.isEmpty(), "a fallback that did not happen is not counted")
        // A5-04: the miss is not silent. It is counted for the day, the pause is given back, and the
        // first one of the day is explained.
        assertEquals(1, rig.misses.current?.count)
        assertEquals(1, rig.media.releases, "the reel the question paused must not stay paused")
        assertEquals(1, rig.notifier.posts)
    }

    @Test
    fun aMissIsCountedOnItsLogicalDay() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        advance(rig, 700)

        val now = rig.clock.now()
        assertEquals(
            LogicalDays.of(now.wallMs, now.zoneId),
            rig.misses.current?.day,
            "the card's day must be the logical day, not the calendar one",
        )
    }

    @Test
    fun onlyTheFirstMissOfTheDayIsExplained() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        advance(rig, 700)

        // The next window change retries the launch and fails the same way. The count climbs; the
        // shade is not written to a second time.
        rig.presenter.show(testUi())
        advance(rig, 700)

        assertEquals(2, rig.misses.current?.count)
        assertEquals(1, rig.notifier.posts)
    }

    @Test
    fun aMissOnANewDayIsExplainedAgain() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        advance(rig, 700)
        assertEquals(1, rig.notifier.posts)

        // Past the 04:00 logical rollover: yesterday's miss must not silence today's.
        rig.clock.set(rig.clock.current.copy(wallMs = rig.clock.current.wallMs + DAY_MS))
        rig.presenter.show(testUi())
        advance(rig, 700)

        assertEquals(2, rig.notifier.posts)
        assertEquals(1, rig.misses.current?.count, "a new day starts its own count")
    }

    @Test
    fun aQuestionThatIsAnsweredBeforeTheWindowMissesNothing() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        advance(rig, 200)
        rig.presenter.dismiss()
        advance(rig, 700)

        assertNull(rig.misses.current)
        assertEquals(0, rig.media.releases)
        assertEquals(0, rig.notifier.posts)
    }

    @Test
    fun aVisibleReportInsideTheWindowKeepsTheOverlayOff() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())

        val waited = 300L
        advance(rig, waited)
        rig.holder.reportVisible(viaOverlay = false)
        advance(rig, 400)

        assertFalse(rig.holder.overlayRequested.value)
        assertEquals(waited, rig.holder.lastLatencyMs.value)
        assertTrue(rig.settings.overlayFallbacks.isEmpty())
    }

    @Test
    fun dismissInsideTheWindowCancelsTheFallback() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())

        advance(rig, 200)
        rig.presenter.dismiss()
        advance(rig, 700)

        assertFalse(rig.holder.overlayRequested.value)
        assertNull(rig.holder.ui.value)
        assertTrue(rig.settings.overlayFallbacks.isEmpty())
    }

    @Test
    fun theSurfaceIsPublishedBeforeTheActivityIsLaunched() = runTest {
        val rig = Rig(backgroundScope)
        val ui = testUi()

        rig.presenter.show(ui)

        assertEquals(ui, rig.holder.ui.value)
        assertEquals(1, rig.launcher.launches)
        assertFalse(rig.holder.overlayRequested.value)
    }

    @Test
    fun updateRedrawsWithoutRestartingTheVisibilityClock() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())

        advance(rig, 300)
        rig.presenter.update(testUi(kind = SurfaceKind.REFLECT))
        advance(rig, 300)
        rig.holder.reportVisible(viaOverlay = false)

        assertEquals(600L, rig.holder.lastLatencyMs.value)
        assertEquals(SurfaceKind.REFLECT, rig.holder.ui.value?.kind)
    }

    @Test
    fun aSecondQuestionGetsItsOwnWindow() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        advance(rig, 700)
        // The activity reports itself in this rig, which is what a working device does; the fallback
        // is off, so the only question here is whether each question gets its own launch.
        rig.holder.reportVisible(viaOverlay = false)
        assertFalse(rig.holder.overlayRequested.value)

        rig.presenter.show(testUi())
        assertFalse(rig.holder.overlayRequested.value, "a new question starts with no overlay")
        advance(rig, 700)

        assertFalse(rig.holder.overlayRequested.value, "and still never asks for one while it is off")
        assertTrue(rig.settings.overlayFallbacks.isEmpty())
        assertEquals(2, rig.launcher.launches)
    }

    @Test
    fun anOverlayReportRecordsTheOverlayLatency() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        advance(rig, 650)

        rig.holder.reportVisible(viaOverlay = true)

        assertEquals(650L, rig.holder.lastLatencyMs.value)
        assertTrue(rig.holder.overlayRequested.value, "the overlay is what is on screen")
    }

    @Test
    fun aQuestionThatArrivesWhileVisibleKeepsTheHostAndCountsNothing() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())
        rig.holder.reportVisible(viaOverlay = true)

        rig.presenter.show(testUi(SurfaceKind.TIME_UP))
        advance(rig, 700)

        // The overlay (or activity) that is already up re-renders the new question; it must not be
        // torn down and re-requested 600 ms later, and no fallback is counted for it.
        assertEquals(SurfaceKind.TIME_UP, rig.holder.ui.value?.kind)
        assertTrue(rig.holder.isVisible())
        assertEquals(1, rig.launcher.launches)
        assertTrue(rig.settings.overlayFallbacks.isEmpty())
    }

    @Test
    fun reportGoneClearsBothFlows() = runTest {
        val rig = Rig(backgroundScope)
        rig.presenter.show(testUi())

        rig.holder.reportGone()

        assertNull(rig.holder.ui.value)
        assertFalse(rig.holder.overlayRequested.value)
    }

    /**
     * Advance the virtual scheduler and the fake wall clock together.
     *
     * The presenter measures latency with the injected [com.ramish.sadiq.core.time.Clock], not with
     * the scheduler, so a test that moved only one of them would prove the wrong thing.
     */
    private fun TestScope.advance(rig: Rig, millis: Long) {
        val current = rig.clock.current
        rig.clock.set(
            current.copy(
                wallMs = current.wallMs + millis,
                elapsedMs = current.elapsedMs + millis
            )
        )
        // A task scheduled at exactly the new current time is left to `runCurrent` below.
        advanceTimeBy(millis)
        runCurrent()
    }

    /** The presenter, its holder and the four seams a test can observe. */
    private class Rig(scope: CoroutineScope) {
        val clock = FakeClock()
        val holder = InterceptStateHolder(clock)
        val settings = FakeSettingsRepository()
        val launcher = RecordingLauncher()
        val media = RecordingDucker()
        val misses = FakeSurfaceMisses()
        val notifier = RecordingNotifier()
        val presenter = SurfacePresenterImpl(
            holder = holder,
            launcher = launcher,
            settings = settings,
            clock = clock,
            mediaDucker = media,
            misses = misses,
            notifier = notifier,
            scope = scope,
        )
    }

    private class RecordingLauncher : InterceptLauncher {
        var launches = 0
            private set

        override fun launch() {
            launches += 1
        }
    }

    /** The duck the question takes and gives back; a miss has to give it back (§3.5, A5-04). */
    private class RecordingDucker : MediaDucker {
        var ducks = 0
            private set
        var releases = 0
            private set

        override fun duck() {
            ducks += 1
        }

        override fun release() {
            releases += 1
        }
    }

    /** The miss record, in memory: the presenter only counts, the Sky card reads. */
    private class FakeSurfaceMisses : SurfaceMisses {
        private val state = MutableStateFlow<SurfaceMiss?>(null)

        override val last: Flow<SurfaceMiss?> = state

        /** What the record holds right now, for assertions. */
        val current: SurfaceMiss? get() = state.value

        override fun record(day: Int): Int {
            val previous = state.value
            val next = if (previous != null && previous.day == day) previous.count + 1 else 1
            state.value = SurfaceMiss(day, next)
            return next
        }
    }

    private class RecordingNotifier : SurfaceMissNotifier {
        var posts = 0
            private set

        override fun questionCouldNotAppear() {
            posts += 1
        }
    }

    private companion object {
        /** One civil day, for the rollover test. */
        const val DAY_MS: Long = 24 * 60 * 60 * 1000L
    }
}
