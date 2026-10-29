package com.ramish.sadiq.guard

import android.app.Notification
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.contract.MediaDucker
import com.ramish.sadiq.contract.SoundController
import com.ramish.sadiq.contract.SurfacePresenter
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.TimeSnapshot
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeGuardController
import com.ramish.sadiq.testing.FakeHistoryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * Every effect the reducer can emit reaches exactly one collaborator, and the right one.
 *
 * The mapping is the whole contract of this class: a `ShowSurface` that also sent the user home, or
 * a `Record` that also played a sound, would be a bug no other test could see, because the engine
 * only ever sees its own state.
 */
class EffectRouterTest {

    @Test
    fun everyEffectReachesExactlyOneCollaborator() = runTest {
        val effects = listOf(
            Effect.ShowSurface(testUi()),
            Effect.UpdateSurface(testUi(SurfaceKind.REFLECT)),
            Effect.DismissSurface,
            Effect.GoHome,
            Effect.OpenSadiq("health"),
            Effect.DuckMedia,
            Effect.ReleaseMedia,
            Effect.Live(LiveState.GrantRunning("com.instagram.android", GrantKind.STAY, 300_000L, 240_000L)),
            Effect.PlaySound(SoundKind.RAIN, 1_200_000L),
            Effect.StopSound,
            Effect.Record(sampleStarLit()),
            Effect.FocusCompleted(5),
        )
        for (effect in effects) {
            val rig = Rig(backgroundScope)
            rig.router.dispatch(effect)
            assertEquals(1, rig.calls(), "$effect touched ${rig.calls()} collaborators")
        }
    }

    @Test
    fun showUpdateAndDismissAllGoToThePresenter() = runTest {
        val rig = Rig(backgroundScope)
        val pause = testUi()
        val reflect = testUi(SurfaceKind.REFLECT)

        rig.router.dispatch(Effect.ShowSurface(pause))
        rig.router.dispatch(Effect.UpdateSurface(reflect))
        rig.router.dispatch(Effect.DismissSurface)

        assertEquals(listOf(pause), rig.surfaces.shown)
        assertEquals(listOf(reflect), rig.surfaces.updated)
        assertEquals(1, rig.surfaces.dismissals)
        assertEquals(0, rig.home.calls)
        assertEquals(0, rig.media.calls)
    }

    @Test
    fun openSadiqCarriesTheRouteToHome() = runTest {
        val rig = Rig(backgroundScope)

        rig.router.dispatch(Effect.OpenSadiq("health"))

        assertEquals(listOf("health"), rig.home.opened)
        assertEquals(0, rig.home.goHomeCalls)
    }

    @Test
    fun liveIsShownWithTheClockReadNow() = runTest {
        val rig = Rig(backgroundScope)
        val state = LiveState.FocusRunning(1_500_000L, 900_000L, SoundKind.RAIN)

        rig.router.dispatch(Effect.Live(state))

        assertEquals(listOf<LiveState>(state), rig.live.shown)
        assertSame(rig.clock.current, rig.live.clockReadings.single())
    }

    @Test
    fun soundEffectsReachThePlayer() = runTest {
        val rig = Rig(backgroundScope)

        rig.router.dispatch(Effect.PlaySound(SoundKind.NIGHT_PAD, null))
        rig.router.dispatch(Effect.StopSound)

        assertEquals(listOf<Pair<SoundKind, Long?>>(SoundKind.NIGHT_PAD to null), rig.sound.played)
        assertEquals(1, rig.sound.stopCount)
    }

    @Test
    fun aRecordIsWrittenOnceAsHistory() = runTest {
        val rig = Rig(backgroundScope)
        val event = sampleStarLit()

        rig.router.dispatch(Effect.Record(event))

        assertEquals(listOf<RecordEvent>(event), rig.history.events)
        assertTrue(rig.live.completed.isEmpty())
    }

    @Test
    fun aFinishedFocusIsAnnouncedWithItsStarCount() = runTest {
        val rig = Rig(backgroundScope)

        rig.router.dispatch(Effect.FocusCompleted(5))

        assertEquals(listOf(5), rig.live.completed)
        assertTrue(rig.history.events.isEmpty())
    }

    @Test
    fun mediaRequestsArePaired() = runTest {
        val rig = Rig(backgroundScope)

        rig.router.dispatch(Effect.DuckMedia)
        rig.router.dispatch(Effect.ReleaseMedia)

        assertEquals(1, rig.media.ducks)
        assertEquals(1, rig.media.releases)
    }

    /** One router, its fakes, and a count of every collaborator call it made. */
    private class Rig(scope: CoroutineScope) {
        val clock = FakeClock()
        val controller: GuardController = FakeGuardController()
        val surfaces = RecordingSurfaces()
        val home = RecordingHome()
        val media = RecordingMedia()
        val live = RecordingLive()
        val sound = RecordingSound()
        val history = FakeHistoryRepository()

        /**
         * The real [Recorder] over a fake history: the recorder is a class whose only dependency is
         * the repository, so testing it here also proves the router writes through it rather than
         * around it.
         */
        val router = EffectRouter(
            controller = controller,
            surfaces = surfaces,
            home = home,
            media = media,
            live = live,
            sound = sound,
            recorder = Recorder(history),
            clock = clock,
            scope = scope,
        )

        fun calls(): Int =
            surfaces.calls + home.calls + media.calls + live.calls + sound.calls + history.events.size
    }

    private class RecordingSurfaces : SurfacePresenter {
        val shown = mutableListOf<InterceptUi>()
        val updated = mutableListOf<InterceptUi>()
        var dismissals = 0
            private set
        val calls: Int get() = shown.size + updated.size + dismissals

        override fun show(ui: InterceptUi) {
            shown += ui
        }

        override fun update(ui: InterceptUi) {
            updated += ui
        }

        override fun dismiss() {
            dismissals += 1
        }
    }

    private class RecordingHome : HomeAction {
        var goHomeCalls = 0
            private set
        val opened = mutableListOf<String>()
        val calls: Int get() = goHomeCalls + opened.size

        override fun openSettings(target: com.ramish.sadiq.contract.SettingsTarget) = Unit

        override fun goHome(): Boolean {
            goHomeCalls += 1
            return true
        }

        override fun openSadiq(route: String) {
            opened += route
        }
    }

    private class RecordingMedia : MediaDucker {
        var ducks = 0
            private set
        var releases = 0
            private set
        val calls: Int get() = ducks + releases

        override fun duck() {
            ducks += 1
        }

        override fun release() {
            releases += 1
        }
    }

    /** Records every call together with the clock reading it was handed. */
    private class RecordingLive : LiveNotifier {
        val shown = mutableListOf<LiveState>()
        val clockReadings = mutableListOf<TimeSnapshot>()
        val completed = mutableListOf<Int>()
        var keepAlives = 0
            private set
        var asleepCount = 0
            private set
        val calls: Int get() = shown.size + completed.size + keepAlives + asleepCount

        override fun show(state: LiveState, now: TimeSnapshot) {
            shown += state
            clockReadings += now
        }

        override fun keepAlive(): Notification {
            keepAlives += 1
            return Notification()
        }

        override fun focusComplete(stars: Int) {
            completed += stars
        }

        override fun asleep() {
            asleepCount += 1
        }
    }

    private class RecordingSound : SoundController {
        val played = mutableListOf<Pair<SoundKind, Long?>>()
        var stopCount = 0
            private set
        val calls: Int get() = played.size + stopCount

        override val playing: StateFlow<SoundKind> = MutableStateFlow(SoundKind.NONE)

        override fun play(kind: SoundKind, durationMs: Long?) {
            played += kind to durationMs
        }

        override fun stop() {
            stopCount += 1
        }

        override fun preview(kind: SoundKind) {
            // The router never previews; a call here would be a routing bug the counter would miss.
        }
    }

    private companion object {
        fun sampleStarLit(): RecordEvent.StarLit = RecordEvent.StarLit(
            StarAward(
                kind = StarKind.LEAVE,
                litWall = FakeClock.EVENING.wallMs,
                logicalDay = 0,
                window = WindowKind.NONE,
                sourcePackage = "com.instagram.android",
                sourceRef = "g1",
            )
        )
    }
}

/**
 * A complete [InterceptUi] for a test: a pause over Instagram with no optional extras.
 *
 * Shared with [SurfacePresenterTest]; every field has to be filled in because the type has no
 * defaults, and duplicating the list twice would make a change to the contract a two-file fix.
 *
 * @param kind the surface kind; [SurfaceKind.PAUSE] by default.
 */
internal fun testUi(kind: SurfaceKind = SurfaceKind.PAUSE): InterceptUi = InterceptUi(
    kind = kind,
    packageName = "com.instagram.android",
    domain = null,
    suggestion = null,
    stayOptionsMin = listOf(2, 5),
    stayRequiresHold = false,
    resumeRemainingMs = null,
    freeScrollOptionsMin = emptyList(),
    freeScrollAvailableMs = 0L,
    quickNeedsLeft = 0,
    budgetRemainingMs = 600_000L,
    restRemainingMs = null,
    restEndsWall = null,
    focusRemainingMs = null,
    prayer = null,
    isJumuah = false,
    nextFajrWall = null,
    windowEndWall = null,
    reflectionOpens = null,
    reflectReason = null,
    timeUpKind = null,
    leaveEarnsStar = true,
    forceDark = false,
    testMode = false,
    mode = Mode.BALANCED,
)
