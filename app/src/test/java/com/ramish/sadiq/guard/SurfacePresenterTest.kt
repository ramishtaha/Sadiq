package com.ramish.sadiq.guard

import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun noVisibleReportWithin600msRequestsTheOverlay() = runTest {
        val rig = Rig(backgroundScope)

        rig.presenter.show(testUi())
        advanceTimeBy(599)
        runCurrent()
        assertFalse(rig.holder.overlayRequested.value, "the activity still has a millisecond")

        advanceTimeBy(2)
        runCurrent()

        assertTrue(rig.holder.overlayRequested.value)
        assertEquals(1, rig.settings.overlayFallbacks.values.sum())
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
        assertTrue(rig.holder.overlayRequested.value)

        rig.presenter.show(testUi())
        assertFalse(rig.holder.overlayRequested.value, "a new question starts with no overlay")
        advance(rig, 700)

        assertTrue(rig.holder.overlayRequested.value)
        assertEquals(2, rig.settings.overlayFallbacks.values.sum())
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

    /** The presenter, its holder and the two seams a test can observe. */
    private class Rig(scope: CoroutineScope) {
        val clock = FakeClock()
        val holder = InterceptStateHolder(clock)
        val settings = FakeSettingsRepository()
        val launcher = RecordingLauncher()
        val presenter = SurfacePresenterImpl(
            holder = holder,
            launcher = launcher,
            settings = settings,
            clock = clock,
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
}
