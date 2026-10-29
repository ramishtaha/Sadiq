package com.ramish.sadiq.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.NotificationContract
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.TimeSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds every notification Sadiq can post (§4.6). No notification is assembled anywhere else, which
 * is what keeps the calm rules in one place: no custom views, no colourised titles, no badge, no
 * per-open notices.
 *
 * The text comes from [LiveText] so the formats stay testable, and the dynamic parts of a countdown
 * are anchored to wall time rather than recomputed here.
 */
@Singleton
class NotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * The quiet line that says Sadiq is running. It is also the foreground service's notification, so
     * it is marked immediate: a service notification would otherwise sit behind the platform's
     * ten-second grace period and the timer would appear late.
     *
     * @param appCount how many apps are guarded, or null when the count is not known here.
     */
    fun keepAlive(appCount: Int?): Notification =
        base(Channels.GUARD_STATUS)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentTitle(context.getString(R.string.notify_keep_alive_title))
            .setContentText(LiveText.guardedLine(appCount))
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openSadiqIntent())
            .build()

    /**
     * The one notification that follows a grant or a focus session. [LiveState.None] falls back to the
     * keep-alive, because id 1 is shared with the foreground service and must never be empty.
     *
     * @param state what the engine is doing.
     * @param now the reading the countdown is anchored to.
     */
    fun live(state: LiveState, now: TimeSnapshot): Notification = when (state) {
        LiveState.None -> keepAlive(appCount = null)
        is LiveState.GrantRunning -> grantRunning(state, now.wallMs)
        is LiveState.GrantHeld -> grantHeld(state.remainingMs, state.explicitPause)
        is LiveState.FocusRunning -> focusRunning(state, now.wallMs)
    }

    /**
     * A grant counting down: "{App} · {5} min", a system chronometer, and the two answers that make
     * sense while the timer runs.
     *
     * @param state the running grant.
     * @param nowWallMs wall time the countdown is anchored to.
     */
    fun grantRunning(state: LiveState.GrantRunning, nowWallMs: Long): Notification {
        val remainingMs = state.remainingMs.coerceAtLeast(0L)
        val builder = liveBuilder()
            .setContentTitle(LiveText.grantTitle(applicationLabel(state.packageName), state.grantedMs))
            .setShowWhen(true)
            .setWhen(nowWallMs + remainingMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(doneEarlyAction())
            // No "Save the rest" action: a paused grant cannot be resumed yet (no button submits
            // Choice.Resume), so offering it promised time the app could not give back. It returns
            // with the Resume button, not before.

        promoteIfSupported(
            builder = builder,
            remainingMs = remainingMs,
            totalMs = state.grantedMs,
            shortText = LiveText.shortRemaining(remainingMs)
        )
        return builder.build()
    }

    /**
     * A grant on hold: no chronometer, because nothing is counting while it waits. A hold the user
     * chose says "saved for later" and offers "Let it go"; an interruption only offers "I'm done".
     *
     * @param remainingMs what is left of the grant.
     * @param explicitPause true when the user tapped "Save the rest".
     */
    fun grantHeld(remainingMs: Long, explicitPause: Boolean): Notification {
        val title = if (explicitPause) LiveText.savedTitle(remainingMs) else LiveText.heldTitle(remainingMs)
        val builder = liveBuilder()
            .setContentTitle(title)
            .setShowWhen(false)
            .addAction(doneEarlyAction())

        if (explicitPause) {
            builder.addAction(action(R.string.notify_action_let_go, NotificationContract.ACTION_DISCARD, RC_LET_GO))
        }
        return builder.build()
    }

    /**
     * A focus session counting down: "Focus · 25 min", a chronometer, and the sound toggle whose label
     * reflects whether anything is playing.
     *
     * @param state the running session.
     * @param nowWallMs wall time the countdown is anchored to.
     */
    fun focusRunning(state: LiveState.FocusRunning, nowWallMs: Long): Notification {
        val remainingMs = state.remainingMs.coerceAtLeast(0L)
        val soundLabel = if (state.sound == SoundKind.NONE) {
            R.string.notify_action_sound_on
        } else {
            R.string.notify_action_sound_off
        }
        val builder = liveBuilder()
            .setContentTitle(LiveText.focusTitle(state.plannedMs))
            .setShowWhen(true)
            .setWhen(nowWallMs + remainingMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(action(soundLabel, NotificationContract.ACTION_TOGGLE_SOUND, RC_TOGGLE_SOUND))
            .addAction(action(R.string.notify_action_end_focus, NotificationContract.ACTION_END_FOCUS, RC_END_FOCUS))

        promoteIfSupported(
            builder = builder,
            remainingMs = remainingMs,
            totalMs = state.plannedMs,
            shortText = LiveText.shortRemaining(remainingMs)
        )
        return builder.build()
    }

    /**
     * "Focus complete" · "+5 stars in your sky", or null when the session lit nothing.
     *
     * The null is the point: a capped session awards no star, and there is no completion line that is
     * true without one, so the caller must not post rather than post "+0 stars" (§2.2). The
     * screen-off rule that decides *whether* to post at all lives in [LiveNotifierImpl].
     *
     * @param stars how many stars the session lit.
     */
    fun focusComplete(stars: Int): Notification? {
        val line = LiveText.starsLine(stars) ?: return null
        return attentionBuilder()
            .setContentTitle(context.getString(R.string.notify_focus_complete_title))
            .setContentText(line)
            .build()
    }

    /** "Sadiq is asleep" · what it cannot do until the user wakes it. At most one per 24 h. */
    fun asleep(): Notification =
        attentionBuilder()
            .setContentTitle(context.getString(R.string.notify_asleep_title))
            .setContentText(context.getString(R.string.notify_asleep_text))
            .build()

    /** Every Sadiq notification shares the small star and never shows its content on a lock screen. */
    private fun base(channelId: String): Notification.Builder =
        Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

    /**
     * The shared shape of id 1. It carries the foreground-service attributes because the service owns
     * that id: an update without them would lose the immediate behaviour and the timer would lag.
     */
    private fun liveBuilder(): Notification.Builder =
        base(Channels.SESSION_LIVE)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openSadiqIntent())

    /** The attention posts are dismissible, silent and tap through to Sadiq. */
    private fun attentionBuilder(): Notification.Builder =
        base(Channels.ATTENTION)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openSadiqIntent())

    /** "I'm done" is on every live grant, running or held. */
    private fun doneEarlyAction(): Notification.Action =
        action(R.string.notify_action_done_early, NotificationContract.ACTION_DONE_EARLY, RC_DONE_EARLY)

    /**
     * A notification action wired to the session receiver. The receiver name comes from
     * [NotificationContract] as a string, so this package never compiles against the receiver class.
     */
    private fun action(@StringRes titleRes: Int, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(action)
            .setComponent(ComponentName(context.packageName, NotificationContract.RECEIVER))
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null as Icon?, context.getString(titleRes), pendingIntent).build()
    }

    /** Tapping any live notification opens Sadiq on its Sky start destination. */
    private fun openSadiqIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            RC_OPEN_SADIQ,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** The app's own name; the package name is the fallback so a title never renders empty. */
    @Suppress("DEPRECATION")
    private fun applicationLabel(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** The one colour a Live Update may use: the theme's primary, for the progress segment. */
    private fun primaryColor(): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorPrimary))
        return try {
            attrs.getColor(0, FALLBACK_PRIMARY)
        } finally {
            attrs.recycle()
        }
    }

    /**
     * Adds the API 36 Live Update decoration to a countdown, or does nothing below 36.
     *
     * Everything the platform needs for promotion is in one place: the request flag, the short
     * critical text ("4m") and a standard [Notification.ProgressStyle] with one primary segment.
     * Below 36 the same builder is a plain ongoing notification with a chronometer, which is the
     * documented fallback and the only thing older platforms understand.
     */
    private fun promoteIfSupported(
        builder: Notification.Builder,
        remainingMs: Long,
        totalMs: Long,
        shortText: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Api36.promote(builder, remainingMs, totalMs, shortText, primaryColor())
        }
    }

    /** API 36 is isolated here so class verification of the shared path never meets these types. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private object Api36 {
        private const val PROGRESS_MAX: Int = 100

        fun promote(
            builder: Notification.Builder,
            remainingMs: Long,
            totalMs: Long,
            shortText: String,
            color: Int
        ) {
            val percent = LiveText.progressPercent(totalMs - remainingMs, totalMs)
            val segment = Notification.ProgressStyle.Segment(PROGRESS_MAX).setColor(color)
            val style = Notification.ProgressStyle()
                .setProgressSegments(listOf(segment))
                .setProgress(percent)
            builder
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(shortText)
                .setStyle(style)
        }
    }

    private companion object {
        /** #1F6E5C, the light primary: the progress segment's colour if the theme resolves nothing. */
        val FALLBACK_PRIMARY: Int = 0xFF1F6E5C.toInt()

        const val RC_OPEN_SADIQ: Int = 10
        const val RC_DONE_EARLY: Int = 11
        const val RC_SAVE_REST: Int = 12
        const val RC_LET_GO: Int = 13
        const val RC_TOGGLE_SOUND: Int = 14
        const val RC_END_FOCUS: Int = 15
    }
}
