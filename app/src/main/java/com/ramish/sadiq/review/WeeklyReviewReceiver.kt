package com.ramish.sadiq.review

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.NotificationContract
import com.ramish.sadiq.core.insight.WeeklyReview
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.notify.Channels
import com.ramish.sadiq.notify.NotificationInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Sunday 11:00 review: one attention notification that a week is ready, nothing more.
 *
 * It is declared `android:enabled="false"` in the manifest so M1 ships without a review, and
 * [WeekInitializer] turns it on at the first start of M2. Once enabled, this receiver re-arms itself
 * at the end of every run — re-arming first, before any work that can fail — so a bad read can cost
 * one review and never the schedule.
 *
 * It posts on the `attention` channel with notification id 2, the id §4.6 reserves for the rare things
 * the user must know about. It posts nothing when the user turned the review off, when notifications
 * are denied, or when Sadiq's records do not yet cover a week: an empty reminder is worse than none.
 */
class WeeklyReviewReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext: Context = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                deliver(appContext)
            } catch (failed: Throwable) {
                // An alarm must never crash the process it woke. The next Sunday tries again.
                Log.w(TAG, "weekly review failed: ${failed.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /** Re-arm, refresh the usage rows, then post only if there is an honest week to announce. */
    private suspend fun deliver(context: Context) {
        val entry = weekEntryPoint(context)

        // First, and outside every condition: next week's review is not this week's business.
        entry.weeklyReviewScheduler().schedule()

        entry.usageDayAggregator().aggregate()

        val settings = entry.settingsRepository()
        if (!settings.prefs.first().weeklyNotif) return

        val now = entry.clock().now()
        val today: Int = LogicalDays.of(now.wallMs, now.zoneId)
        val input = entry.historyRepository().weekInput(
            WeekCoverage.reviewDays(today),
            WeekCoverage.comparisonDays(today),
            settings.config.value,
        )
        if (!WeekCoverage.isWeekReady(input)) return

        val review: WeeklyReview = entry.weeklyReviewBuilder().build(input)
        post(context, review)
    }

    /** One quiet line: how long the week was, and where to read it. */
    private fun post(context: Context, review: WeeklyReview) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        // Idempotent, and the only way this cold process can be sure the channel exists.
        NotificationInitializer.createChannels(context)

        val notification: Notification = Notification.Builder(context, Channels.ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.week_notify_title))
            .setContentText(
                context.getString(R.string.week_notify_text, WeekFormat.duration(review.totalMs))
            )
            .setContentIntent(openSadiq(context))
            .build()

        runCatching {
            manager.notify(NotificationContract.ID_ATTENTION, notification)
        }.onFailure { refused ->
            // A shade the user has closed, or a permission revoked between the check and the post.
            Log.w(TAG, "weekly review not posted: ${refused.message}")
        }
    }

    /**
     * Tapping the review opens Sadiq. There is no deep link: the app's launch intent is the one
     * entry point that exists, and the Week tab is one tap from where it lands.
     */
    private fun openSadiq(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            RC_OPEN_SADIQ,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private companion object {

        /** Outlives one receiver instance; the system throws the object away after `onReceive`. */
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val RC_OPEN_SADIQ: Int = 32

        const val TAG = "SQ/Week"
    }
}
