package com.ramish.sadiq.review

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ramish.sadiq.core.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the Sunday 11:00 weekly review.
 *
 * Inexact on purpose (`setAndAllowWhileIdle`): the review is a reading of a week, and an hour of slop
 * on a Sunday morning is invisible, while `setExactAndAllowWhileIdle` would add a special-access
 * dialog for a timer nobody is watching. The plan asks for the same call.
 *
 * The alarm survives a reboot only as a PendingIntent that no longer has a receiver process, so
 * [WeekInitializer] re-arms it at every process start, and [WeeklyReviewReceiver] re-arms it every
 * time it fires. Missing both would silently end the review after the first week.
 */
@Singleton
class WeeklyReviewScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {

    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    /**
     * Arm the next review, replacing any earlier request.
     *
     * @return the wall time it was armed for, or null when the platform refused the alarm.
     */
    fun schedule(): Long? {
        val now = clock.now()
        val triggerAtWall: Long = WeeklyReviewTime.nextFireWall(now.wallMs, now.zoneId)
        return try {
            alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWall, pendingIntent())
            triggerAtWall
        } catch (refused: RuntimeException) {
            // A ROM that refuses alarms loses only the reminder: the Week tab still works, and the
            // next process start tries again.
            Log.w(TAG, "weekly review alarm not set: ${refused.message}")
            null
        }
    }

    /** Drop the armed review, for a user who turned the notification off. */
    fun cancel() {
        runCatching { alarms?.cancel(pendingIntent()) }
    }

    /**
     * An explicit broadcast to [WeeklyReviewReceiver].
     *
     * `FLAG_UPDATE_CURRENT` so a re-arm reuses the one PendingIntent instead of leaving stale copies
     * with older trigger times in the alarm queue.
     */
    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, WeeklyReviewReceiver::class.java)
            .setAction(ACTION_WEEKLY_REVIEW)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {

        /** The alarm's action. The intent is explicit, so this never leaves the app. */
        const val ACTION_WEEKLY_REVIEW: String = "com.ramish.sadiq.WEEKLY_REVIEW"

        /** One review, so one request code. */
        private const val REQUEST_CODE: Int = 31

        private const val TAG = "SQ/Week"
    }
}
