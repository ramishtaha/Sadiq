package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.sound.SoundKind

/**
 * What the user can see about a grant or a focus session while it runs: the status-bar chip, the
 * ongoing notification and, on API 36, the Live Update.
 */
sealed interface LiveState {
    /** Nothing running; the keep-alive notification goes back to its silent minimum. */
    data object None : LiveState

    /**
     * A grant is counting down.
     *
     * @property packageName the guarded app in front.
     * @property kind what the time was granted for.
     * @property grantedMs the length that was chosen.
     * @property remainingMs what is left.
     */
    data class GrantRunning(
        val packageName: String,
        val kind: GrantKind,
        val grantedMs: Long,
        val remainingMs: Long
    ) : LiveState

    /**
     * A grant is held: the user left the app or turned the screen off, and has a grace period.
     *
     * @property remainingMs what is left of the grant.
     * @property explicitPause true when the user tapped "Save the rest", false for an implicit hold.
     */
    data class GrantHeld(
        val remainingMs: Long,
        val explicitPause: Boolean
    ) : LiveState

    /**
     * A focus session is running.
     *
     * @property plannedMs the length that was chosen.
     * @property remainingMs what is left.
     * @property sound the soundscape playing, [SoundKind.NONE] for silence.
     */
    data class FocusRunning(
        val plannedMs: Long,
        val remainingMs: Long,
        val sound: SoundKind
    ) : LiveState
}

/**
 * A fact worth remembering. History is written from these, never from the UI's impressions, so a
 * surface that failed to draw still leaves an accurate record.
 */
sealed interface RecordEvent {
    /**
     * A guarded app was opened and a surface appeared.
     *
     * @property at wall time of the open.
     * @property logicalDay the logical day it belongs to.
     * @property packageName the app that was opened.
     * @property domain the matched guarded domain, if any.
     * @property surface which surface was shown.
     * @property outcome what the user did.
     * @property decisionMs how long the surface was up before it was answered, when it was.
     * @property grantId the grant created by the answer, if any.
     */
    data class Open(
        val at: Long,
        val logicalDay: Int,
        val packageName: String,
        val domain: String?,
        val surface: SurfaceKind,
        val outcome: OpenOutcome,
        val decisionMs: Long?,
        val grantId: String?
    ) : RecordEvent

    /**
     * A grant stopped.
     *
     * @property grant the grant as it stood at the end.
     * @property endedAt wall time it ended.
     * @property end why it ended.
     * @property logicalDay the logical day it ended on.
     */
    data class GrantClosed(
        val grant: Grant,
        val endedAt: Long,
        val end: GrantEnd,
        val logicalDay: Int
    ) : RecordEvent

    /** A star lit. @property award the star. */
    data class StarLit(val award: StarAward) : RecordEvent

    /**
     * A focus session stopped.
     *
     * @property focus the session as it stood at the end.
     * @property endedAt wall time it ended.
     * @property completed whether the planned time was reached.
     * @property stars how many stars it lit.
     * @property logicalDay the logical day it ended on.
     */
    data class FocusClosed(
        val focus: FocusState,
        val endedAt: Long,
        val completed: Boolean,
        val stars: Int,
        val logicalDay: Int
    ) : RecordEvent

    /**
     * A check-in was answered.
     *
     * @property at wall time of the answer.
     * @property logicalDay the logical day it belongs to.
     * @property reason the reason the user picked.
     * @property packageName the app that was open.
     * @property opensInWindow guarded opens in that window, for the weekly review.
     */
    data class Reflected(
        val at: Long,
        val logicalDay: Int,
        val reason: ReflectionReason,
        val packageName: String,
        val opensInWindow: Int
    ) : RecordEvent
}

/** What the user did with an open, as stored on `open_events`. */
enum class OpenOutcome {
    LEFT,
    LEFT_GESTURE,
    SCREEN_OFF,
    STAYED,
    RESUMED,
    FREE_SCROLL,
    QUICK_NEED,
    FOCUS_STARTED,
    WIND_DOWN,
    DISMISSED,

    /** The user opened guarded time through "Open anyway" (§A.9, T37). Never earns a star. */
    OVERRIDE
}

/**
 * A side effect the reducer asks for. The reducer never performs one itself; the app's effect router
 * does, and tests read the list.
 */
sealed interface Effect {
    /** Put a surface on screen. @property ui the surface. */
    data class ShowSurface(val ui: InterceptUi) : Effect

    /** Redraw the surface that is already on screen. @property ui the new surface. */
    data class UpdateSurface(val ui: InterceptUi) : Effect

    /** Take the surface away. */
    data object DismissSurface : Effect

    /** Send the user home. */
    data object GoHome : Effect

    /** Bring Sadiq itself to the front on [route]. */
    data class OpenSadiq(val route: String) : Effect

    /** Lower other apps' audio for the duration of the surface. */
    data object DuckMedia : Effect

    /** Give the audio back. */
    data object ReleaseMedia : Effect

    /** Update the ongoing notification. @property state what to show. */
    data class Live(val state: LiveState) : Effect

    /**
     * Play a soundscape.
     *
     * @property kind which sound; [SoundKind.NONE] means silence.
     * @property durationMs how long to play, or null to play until stopped.
     */
    data class PlaySound(val kind: SoundKind, val durationMs: Long?) : Effect

    /** Stop whatever is playing. */
    data object StopSound : Effect

    /** Write a fact to history. @property event the fact. */
    data class Record(val event: RecordEvent) : Effect

    /** Announce a finished focus session. @property stars how many stars it lit. */
    data class FocusCompleted(val stars: Int) : Effect
}
