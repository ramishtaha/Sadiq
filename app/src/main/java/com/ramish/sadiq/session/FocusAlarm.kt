package com.ramish.sadiq.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The screen-off backup for the end of a focus session (§3.6, "Timers").
 *
 * `DeadlineScheduler` already keeps an in-process `delay` for the focus end, and that is the timer
 * that normally closes the session. It dies with the process, though, and a focus session is
 * twenty-five minutes of the phone face down — the one stretch where HyperOS is most likely to
 * freeze the app and where the user is least likely to touch anything that would wake it. This alarm
 * is the backstop: it wakes the process, delivers a `GuardInput.Tick`, and the engine closes an
 * expired session on the next input as it always does.
 *
 * It is deliberately **inexact** (`setAndAllowWhileIdle`, not `setExactAndAllowWhileIdle`): a minute
 * of slop on a focus timer is invisible, and avoiding the exact-alarm permission keeps Sadiq from
 * asking for another special access it does not need.
 *
 * The alarm is delivered as an explicit broadcast to `NotificationActionReceiver`, not as a service
 * start. A broadcast PendingIntent from the alarm clock is always delivered, whereas starting a
 * service from the background is refused on Android 12+ unless the app holds a foreground-start
 * exemption — and the case this alarm exists for is precisely the one where the accessibility
 * service (Sadiq's usual exemption) is gone with the process.
 */
@Singleton
class FocusAlarm @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock
) {

    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** The session whose end is already armed, so a state change does not re-arm it every tick. */
    private var armedFor: String? = null

    /**
     * Arm the alarm at the end of [focus], replacing any earlier one.
     *
     * Idempotent per session: the engine republishes the focus state on every input, and re-arming
     * an inexact alarm dozens of times would only make its delivery less predictable.
     *
     * @param focus the running session.
     */
    fun schedule(focus: FocusState) {
        if (armedFor == focus.id) return
        // The remaining time the engine accounted for, not `startedWall + plannedMs`: the wall clock
        // can be moved, and the accounting is the only honest measure of how much is left.
        val remainingMs = (focus.plannedMs - focus.elapsedMs).coerceAtLeast(0L)
        val triggerAtWall = clock.now().wallMs + remainingMs
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWall, pendingIntent())
            armedFor = focus.id
        } catch (refused: RuntimeException) {
            // A ROM that refuses the alarm leaves the in-process deadline as the only timer, which
            // still closes the session while Sadiq is alive. Nothing else changes.
            Log.w(TAG, "focus alarm not set: ${refused.message}")
        }
    }

    /** Drop the alarm, when the session ended before its planned time or was never started. */
    fun cancel() {
        if (armedFor == null) return
        alarms.cancel(pendingIntent())
        armedFor = null
    }

    /**
     * The alarm's PendingIntent: an explicit broadcast to the session receiver.
     *
     * `FLAG_IMMUTABLE` because the intent never changes after creation, and `FLAG_UPDATE_CURRENT` so
     * a re-arm updates the one PendingIntent instead of leaving a second stale one behind.
     */
    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_FOCUS_ALARM)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {

        /**
         * The action the alarm carries.
         *
         * It lives here rather than in `NotificationContract` because it is not part of the app's
         * external surface: the intent is explicit (it names the component) and no other app can send
         * it. `NotificationContract` is the vocabulary of the notifications' actions, which the
         * shade carries across an update; this one only ever travels between Sadiq and itself.
         */
        const val ACTION_FOCUS_ALARM: String = "com.ramish.sadiq.FOCUS_ALARM"

        /** One request code: there is never more than one focus session, so there is never more than one alarm. */
        private const val REQUEST_CODE: Int = 21

        private const val TAG = "SP/Session"
    }
}
