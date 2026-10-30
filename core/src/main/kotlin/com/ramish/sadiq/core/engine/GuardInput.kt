package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.sound.SoundKind

/**
 * Everything that can move the engine. The app layer turns platform events and UI taps into these
 * and nothing else, so the reducer stays a pure function.
 */
sealed interface GuardInput {
    /**
     * The app in front changed.
     *
     * @property packageName the package now in front, or null when nothing qualifies.
     * @property domain the matched guarded domain, when a browser is in front.
     * @property surface which surface of the app is in front.
     */
    data class ForegroundChanged(
        val packageName: String?,
        val domain: String? = null,
        val surface: AppSurface = AppSurface.APP
    ) : GuardInput

    /** The screen went off. Ends any prompt and holds any grant. */
    data object ScreenOff : GuardInput

    /** The screen came on and the user is present (`USER_PRESENT`). */
    data object ScreenOn : GuardInput

    /** A timer fired, or an input arrived that only needs the clock to be read. */
    data object Tick : GuardInput

    /** The user tapped something on a surface or a notification. */
    data class Chose(val choice: Choice) : GuardInput

    /** A focus session was asked for from the Focus tab or a surface. */
    data class StartFocus(val plannedMin: Int, val sound: SoundKind) : GuardInput

    /** Usage stats recovered after the process was down. */
    data class Reconcile(val spans: List<UsageSpan>) : GuardInput

    /** The onboarding or Health-check self-test: arm, then open the app and let the real path run. */
    data class SelfTest(val packageName: String, val returnRoute: String) : GuardInput
}

/** Every answer the user can give to a surface or a notification action. */
sealed interface Choice {
    /** "No, take me home." */
    data object Leave : Choice

    /** "Yes, I need it" for [minutes]. */
    data class Stay(val minutes: Int) : Choice

    /** Resume a grant that was saved for later. */
    data object Resume : Choice

    /** Spend [minutes] of today's free scroll. */
    data class FreeScroll(val minutes: Int) : Choice

    /** The one minute that is always allowed, from a rest or a protected time. */
    data object QuickNeed : Choice

    /** "I'm done": close the grant early and light the star now. */
    data object DoneEarly : Choice

    /** "Save the rest": keep the remaining minutes for later. */
    data object PauseGrant : Choice

    /** "Let it go": drop a saved grant. */
    data object DiscardPaused : Choice

    /** Answer a check-in with [reason]. */
    data class Reflect(val reason: ReflectionReason) : Choice

    /** Start a focus session right now instead of scrolling. */
    data class StartFocusNow(val minutes: Int) : Choice

    /** Wind down with rain instead of scrolling. */
    data object WindDown : Choice

    /** Go back to the focus session that was interrupted. */
    data object BackToFocus : Choice

    /** End the running focus session. */
    data object EndFocus : Choice

    /**
     * "Open anyway": switch the surface to the confirm view and start the wait (§A.2, T36). No
     * record and no economy call — arming is not opening.
     */
    data object ArmOverride : Choice

    /** Hold through the cost line to open anyway, with [reason] as the honest answer (§A.2, T37). */
    data class Override(val reason: ReflectionReason) : Choice
}

/**
 * A stretch of foreground time reported by usage stats.
 *
 * Only used to charge guarded use that happened while the process was not running; the package name
 * is kept, a domain never is (§3.9).
 *
 * @property packageName the app that was in front.
 * @property startWall span start in wall milliseconds.
 * @property endWall span end in wall milliseconds.
 */
data class UsageSpan(
    val packageName: String,
    val startWall: Long,
    val endWall: Long
)
