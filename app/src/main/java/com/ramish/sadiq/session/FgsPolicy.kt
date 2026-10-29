package com.ramish.sadiq.session

import android.content.pm.ServiceInfo
import com.ramish.sadiq.core.engine.EngineState

/**
 * What the session service must be doing right now.
 *
 * @property run whether the service should be in the foreground at all.
 * @property types the `foregroundServiceType` bits to pass to `startForeground`, or 0 when [run] is false.
 */
data class FgsNeed(
    val run: Boolean,
    val types: Int
)

/**
 * The rule that decides whether `SessionService` is a foreground service, and with which types (§3.5).
 *
 * The service exists for three reasons, and each one maps to a declared type:
 * - the quiet keep-alive the user asked for (`keep_alive`), which is a `specialUse` service because
 *   the platform has no category for "a self-chosen time limit that must keep counting";
 * - a grant or a focus session that has to keep counting while the screen is off — also `specialUse`,
 *   and it does not depend on the preference: a timer the user started must survive even if they
 *   turned the silent notification off;
 * - a soundscape that is playing, which is what `mediaPlayback` is for and the only reason the
 *   service touches the audio subsystem.
 *
 * The function is pure: it reads nothing but its arguments, so every combination is a unit test and
 * the service's behaviour can be explained without a device. It is called on every engine state
 * change, on every sound change and on every change of the keep-alive preference, which is why it
 * must not allocate anything expensive or look at the clock.
 */
object FgsPolicy {

    /**
     * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (0x40000000, API 34): the keep-alive, a grant, a focus
     * session. The platform's own constant rather than a literal, and its value is pinned by a test.
     */
    val TYPE_SPECIAL_USE: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    /** `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` (0x2, API 29): a soundscape is playing. */
    val TYPE_MEDIA_PLAYBACK: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    /**
     * The types the service must declare.
     *
     * A grant in `HELD` or `PAUSED` still counts as a grant: its remaining minutes are kept for
     * later, and the notification that offers "I'm done" or "Let it go" has to stay in the shade
     * (§3.6 T23–T27). A soundscape with nothing else running is a foreground service on its own —
     * otherwise a wind-down that outlives its prompt would be killed with the screen off.
     *
     * @param state the engine state, read for a grant or a focus session.
     * @param keepAlive the user's `keep_alive` preference.
     * @param soundPlaying whether a soundscape is currently playing.
     * @return whether to run, and with which types.
     */
    fun required(state: EngineState, keepAlive: Boolean, soundPlaying: Boolean): FgsNeed {
        var types = 0
        if (keepAlive || state.grant != null || state.focus != null) {
            types = types or TYPE_SPECIAL_USE
        }
        if (soundPlaying) {
            types = types or TYPE_MEDIA_PLAYBACK
        }
        return FgsNeed(run = types != 0, types = types)
    }
}
