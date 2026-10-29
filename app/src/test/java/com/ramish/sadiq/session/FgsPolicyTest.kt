package com.ramish.sadiq.session

import android.content.pm.ServiceInfo
import com.ramish.sadiq.contract.NotificationContract
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.engine.Prompt
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.testing.FakeGuardController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Every combination of [FgsPolicy]'s three inputs (§3.5, WP-16).
 *
 * The policy is a pure function of the engine state, the keep-alive preference and the sound, so the
 * whole table can be held here: sixteen cases for the four booleans, plus the named cases that say
 * out loud why each one exists. No clock, no Android service and no coroutine is involved; the only
 * platform reference is the `foregroundServiceType` bit values, which are compile-time constants.
 */
class FgsPolicyTest {

    @Test
    fun keepAliveAloneAsksForSpecialUse() {
        val need = FgsPolicy.required(EngineStateFixture.idle(), keepAlive = true, soundPlaying = false)

        assertTrue(need.run)
        assertEquals(FgsPolicy.TYPE_SPECIAL_USE, need.types)
    }

    @Test
    fun aStayGrantKeepsTheServiceRunningWithoutKeepAlive() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.GRANTED,
            grant = EngineStateFixture.grant(GrantKind.STAY)
        )

        val need = FgsPolicy.required(state, keepAlive = false, soundPlaying = false)

        assertTrue(need.run, "a running grant must survive the keep-alive being off")
        assertEquals(FgsPolicy.TYPE_SPECIAL_USE, need.types)
    }

    @Test
    fun aHeldOrPausedGrantStillNeedsTheService() {
        val grant = EngineStateFixture.grant(GrantKind.STAY)

        val held = FgsPolicy.required(
            EngineStateFixture.idle().copy(phase = Phase.HELD, grant = grant),
            keepAlive = false,
            soundPlaying = false
        )
        val paused = FgsPolicy.required(
            EngineStateFixture.idle().copy(phase = Phase.PAUSED, grant = grant),
            keepAlive = false,
            soundPlaying = false
        )

        assertTrue(held.run, "a held grant keeps its minutes for three minutes of grace")
        assertTrue(paused.run, "a saved grant waits up to an hour for the user to come back")
        assertEquals(FgsPolicy.TYPE_SPECIAL_USE, held.types)
        assertEquals(FgsPolicy.TYPE_SPECIAL_USE, paused.types)
    }

    @Test
    fun aFocusSessionKeepsTheServiceRunningWithoutKeepAlive() {
        val state = EngineStateFixture.idle().copy(focus = EngineStateFixture.focus(SoundKind.RAIN))

        val need = FgsPolicy.required(state, keepAlive = false, soundPlaying = false)

        assertTrue(need.run)
        assertEquals(FgsPolicy.TYPE_SPECIAL_USE, need.types, "the session's own sound is not playing yet")
    }

    @Test
    fun soundAloneAsksForMediaPlaybackOnly() {
        val need = FgsPolicy.required(EngineStateFixture.idle(), keepAlive = false, soundPlaying = true)

        assertTrue(need.run, "a wind-down that outlives its prompt must not be frozen")
        assertEquals(FgsPolicy.TYPE_MEDIA_PLAYBACK, need.types)
    }

    @Test
    fun soundDuringAGrantAsksForBothTypes() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.GRANTED,
            grant = EngineStateFixture.grant(GrantKind.FREE)
        )

        val need = FgsPolicy.required(state, keepAlive = false, soundPlaying = true)

        assertEquals(
            FgsPolicy.TYPE_SPECIAL_USE or FgsPolicy.TYPE_MEDIA_PLAYBACK,
            need.types
        )
    }

    @Test
    fun nothingRunningStopsTheService() {
        val need = FgsPolicy.required(EngineStateFixture.idle(), keepAlive = false, soundPlaying = false)

        assertFalse(need.run, "there is nothing to keep alive")
        assertEquals(0, need.types)
    }

    @Test
    fun runIsTrueExactlyWhenATypeIsAskedFor() {
        for (keepAlive in BOOLEANS) {
            for (soundPlaying in BOOLEANS) {
                val need = FgsPolicy.required(EngineStateFixture.idle(), keepAlive, soundPlaying)
                assertEquals(need.types != 0, need.run, "keepAlive=$keepAlive sound=$soundPlaying")
            }
        }
    }

    @Test
    fun everyCombinationOfKeepAliveGrantFocusAndSoundIsCovered() {
        var checked = 0

        for (keepAlive in BOOLEANS) {
            for (hasGrant in BOOLEANS) {
                for (hasFocus in BOOLEANS) {
                    for (soundPlaying in BOOLEANS) {
                        val state = EngineStateFixture.idle().copy(
                            phase = if (hasGrant) Phase.GRANTED else Phase.IDLE,
                            grant = if (hasGrant) EngineStateFixture.grant(GrantKind.STAY) else null,
                            focus = if (hasFocus) EngineStateFixture.focus(SoundKind.NONE) else null
                        )
                        val need = FgsPolicy.required(state, keepAlive, soundPlaying)
                        val label = "keepAlive=$keepAlive grant=$hasGrant focus=$hasFocus sound=$soundPlaying"

                        val wantsSpecialUse = keepAlive || hasGrant || hasFocus
                        assertEquals(
                            wantsSpecialUse,
                            (need.types and FgsPolicy.TYPE_SPECIAL_USE) != 0,
                            "specialUse bit for $label"
                        )
                        assertEquals(
                            soundPlaying,
                            (need.types and FgsPolicy.TYPE_MEDIA_PLAYBACK) != 0,
                            "mediaPlayback bit for $label"
                        )
                        assertEquals(
                            wantsSpecialUse || soundPlaying,
                            need.run,
                            "run for $label"
                        )
                        assertEquals(
                            need.types and (FgsPolicy.TYPE_SPECIAL_USE or FgsPolicy.TYPE_MEDIA_PLAYBACK),
                            need.types,
                            "no type beyond the two the manifest declares, for $label"
                        )
                        checked += 1
                    }
                }
            }
        }

        assertEquals(16, checked, "2 keep-alive x 2 grant x 2 focus x 2 sound")
    }

    @Test
    fun theTypeBitsAreThePlatformsOwn() {
        // The manifest declares `specialUse|mediaPlayback`; if these ever drift, startForeground
        // throws or silently loses the sound, so the values are pinned rather than trusted.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, FgsPolicy.TYPE_SPECIAL_USE)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, FgsPolicy.TYPE_MEDIA_PLAYBACK)
        assertEquals(0x40000000, FgsPolicy.TYPE_SPECIAL_USE)
        assertEquals(0x2, FgsPolicy.TYPE_MEDIA_PLAYBACK)
    }

    private companion object {
        val BOOLEANS: List<Boolean> = listOf(false, true)
    }
}

/**
 * The five notification buttons, and nothing else (§4.6, WP-16).
 *
 * [sessionActionOf] is the only place the shade's strings become engine inputs, and a wrong row here
 * fails silently on a device — the button closes the shade and nothing happens. So every action the
 * app can post is pinned to the exact [Choice] the equivalent surface button submits.
 *
 * It lives beside [FgsPolicyTest] because WP-16 owns one test path and both subjects belong to it.
 */
class SessionActionTest {

    @Test
    fun theFiveNotificationButtonsSubmitTheChoicesTheSurfacesSubmit() {
        assertEquals(
            SessionAction.ToEngine(GuardInput.Chose(Choice.DoneEarly)),
            sessionActionOf(NotificationContract.ACTION_DONE_EARLY),
            "\"I'm done\" closes the grant early"
        )
        assertEquals(
            SessionAction.ToEngine(GuardInput.Chose(Choice.PauseGrant)),
            sessionActionOf(NotificationContract.ACTION_PAUSE),
            "\"Save the rest\" is a pause of a Stay grant"
        )
        assertEquals(
            SessionAction.ToEngine(GuardInput.Chose(Choice.DiscardPaused)),
            sessionActionOf(NotificationContract.ACTION_DISCARD),
            "\"Let it go\" drops the saved grant"
        )
        assertEquals(
            SessionAction.ToEngine(GuardInput.Chose(Choice.EndFocus)),
            sessionActionOf(NotificationContract.ACTION_END_FOCUS),
            "\"End\" ends the focus session"
        )
        assertEquals(
            SessionAction.ToggleSound,
            sessionActionOf(NotificationContract.ACTION_TOGGLE_SOUND),
            "the sound button drives the player, not the engine"
        )
    }

    @Test
    fun noTwoButtonsSubmitTheSameChoice() {
        val actions = listOf(
            NotificationContract.ACTION_DONE_EARLY,
            NotificationContract.ACTION_PAUSE,
            NotificationContract.ACTION_DISCARD,
            NotificationContract.ACTION_END_FOCUS,
            NotificationContract.ACTION_TOGGLE_SOUND
        )

        for (action in actions) {
            assertTrue(
                sessionActionOf(action) != SessionAction.Ignore,
                "$action is posted by NotificationFactory and must be handled"
            )
        }
        assertEquals(
            actions.size,
            actions.map { sessionActionOf(it) }.toSet().size,
            "one button, one meaning"
        )
    }

    @Test
    fun theFocusAlarmWakesTheEngineInsteadOfAnsweringASurface() {
        assertEquals(SessionAction.Tick, sessionActionOf(FocusAlarm.ACTION_FOCUS_ALARM))
    }

    @Test
    fun anythingElseIsIgnored() {
        assertEquals(SessionAction.Ignore, sessionActionOf(null), "an intent without an action")
        assertEquals(SessionAction.Ignore, sessionActionOf(""), "an empty action")
        assertEquals(
            SessionAction.Ignore,
            sessionActionOf("android.intent.action.SCREEN_OFF"),
            "a system broadcast that is not ours"
        )
        assertEquals(
            SessionAction.Ignore,
            sessionActionOf("${NotificationContract.ACTION_DONE_EARLY} "),
            "a near miss is not a match"
        )
    }
}

/**
 * The notification the foreground service opens with (§3.5, WP-16).
 *
 * [liveStateFor] decides whether the foreground entry shows the quiet keep-alive or the running
 * countdown, and which app and phase the countdown names. None of it needs Android, so the whole
 * reading is pinned here; without it, a paused grant could be announced as a running one.
 */
class SessionNotificationTest {

    @Test
    fun nothingRunningFallsBackToTheKeepAlive() {
        assertEquals(LiveState.None, liveStateFor(EngineStateFixture.idle()))
    }

    @Test
    fun aRunningGrantCountsDownUnderTheAppInFront() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.GRANTED,
            foregroundPackage = YOUTUBE,
            grant = EngineStateFixture.grant(GrantKind.STAY)
        )

        assertEquals(
            LiveState.GrantRunning(YOUTUBE, GrantKind.STAY, GRANTED_MS, REMAINING_MS),
            liveStateFor(state)
        )
    }

    @Test
    fun aGrantWithNothingInFrontNamesTheAppItStartedWith() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.GRANTED,
            grant = EngineStateFixture.grant(GrantKind.FREE)
        )

        assertEquals(
            LiveState.GrantRunning(INSTAGRAM, GrantKind.FREE, GRANTED_MS, REMAINING_MS),
            liveStateFor(state)
        )
    }

    @Test
    fun aHeldGrantIsAnInterruptionAndNotASavedOne() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.HELD,
            grant = EngineStateFixture.grant(GrantKind.STAY)
        )

        assertEquals(
            LiveState.GrantHeld(REMAINING_MS, explicitPause = false),
            liveStateFor(state),
            "an interruption only offers \"I'm done\", not \"Let it go\""
        )
    }

    @Test
    fun aPausedGrantSaysSavedForLater() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.PAUSED,
            grant = EngineStateFixture.grant(GrantKind.STAY)
        )

        assertEquals(LiveState.GrantHeld(REMAINING_MS, explicitPause = true), liveStateFor(state))
    }

    @Test
    fun aPromptOverAPausedGrantStillSaysSavedForLater() {
        // T04: the prompt is up, but the grant behind it is the paused one and stays resumable.
        val state = EngineStateFixture.idle().copy(
            phase = Phase.PROMPTING,
            prompt = Prompt(
                kind = SurfaceKind.PAUSE,
                packageName = YOUTUBE,
                domain = null,
                shownWall = 1_000L,
                reflectReason = null,
                testMode = false,
                returnRoute = null
            ),
            grant = EngineStateFixture.grant(GrantKind.STAY).copy(pausedSinceElapsed = 1_000L)
        )

        assertEquals(LiveState.GrantHeld(REMAINING_MS, explicitPause = true), liveStateFor(state))
    }

    @Test
    fun aFocusSessionOutranksTheGrantItSuperseded() {
        val state = EngineStateFixture.idle().copy(
            phase = Phase.GRANTED,
            grant = EngineStateFixture.grant(GrantKind.STAY),
            focus = EngineStateFixture.focus(SoundKind.RAIN)
        )

        assertEquals(
            LiveState.FocusRunning(PLANNED_MS, PLANNED_MS - ELAPSED_MS, SoundKind.RAIN),
            liveStateFor(state)
        )
    }

    @Test
    fun anOverrunFocusNeverCountsBelowZero() {
        val state = EngineStateFixture.idle().copy(
            focus = EngineStateFixture.focus(SoundKind.NONE).copy(elapsedMs = PLANNED_MS + 1L)
        )

        assertEquals(LiveState.FocusRunning(PLANNED_MS, 0L, SoundKind.NONE), liveStateFor(state))
    }
}

/** One minute, the unit every duration in this file is written in. */
private const val MINUTE_MS: Long = 60_000L

/** The app the fixture's grant started in. */
private const val INSTAGRAM: String = "com.instagram.android"

/** The app a grant is looked at from, when one is in front. */
private const val YOUTUBE: String = "com.youtube.android"

/** A five-minute grant, one minute of it already spent. */
private const val GRANTED_MS: Long = 5 * MINUTE_MS

/** What is left of that grant. */
private const val REMAINING_MS: Long = GRANTED_MS - MINUTE_MS

/** A twenty-five minute focus session, five minutes in. */
private const val PLANNED_MS: Long = 25 * MINUTE_MS

/** How much of it has elapsed. */
private const val ELAPSED_MS: Long = 5 * MINUTE_MS

/**
 * The states the policy and the notification reading are asked about.
 *
 * Built from the app's own idle fixture so a change to `EngineState` cannot leave this file
 * constructing a state the engine would never produce.
 */
private object EngineStateFixture {

    fun idle(): EngineState = FakeGuardController.IDLE

    fun grant(kind: GrantKind): Grant = Grant(
        id = "g7",
        kind = kind,
        grantedMs = GRANTED_MS,
        consumedMs = MINUTE_MS,
        startedWall = 1_000L,
        lastAccountedElapsed = 1_000L,
        heldSinceElapsed = null,
        pausedSinceElapsed = null,
        windowAtStart = WindowKind.NONE,
        firstPackage = INSTAGRAM
    )

    fun focus(sound: SoundKind): FocusState = FocusState(
        id = "f7",
        plannedMs = PLANNED_MS,
        elapsedMs = ELAPSED_MS,
        lastAccountedElapsed = ELAPSED_MS,
        startedWall = 1_000L,
        sound = sound
    )
}
