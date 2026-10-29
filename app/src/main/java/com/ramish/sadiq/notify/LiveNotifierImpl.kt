package com.ramish.sadiq.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.contract.NotificationContract
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.TimeSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one writer of notification id 1 while a grant or a focus session is alive, and of id 2 for the
 * rare attention posts.
 *
 * The engine already emits [com.ramish.sadiq.core.engine.Effect.Live] only on state changes, but the
 * remaining time inside a state moves; a plain re-post on every emission would rewrite the status bar
 * each second and break §4.6's "updates are posted only on state changes". The signature below
 * therefore compares states in ten-second buckets: the chronometer stays exact because it is anchored
 * to wall time, while a redundant post is dropped.
 */
@Singleton
class LiveNotifierImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val factory: NotificationFactory
) : LiveNotifier {

    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    /**
     * The power manager the completion rule asks, read from the application context this notifier
     * already holds — the core module must not grow an Android dependency for it.
     *
     * Nullable only because `getSystemService` is: a missing manager reads as "screen on", which keeps
     * the announcement on the Focus screen rather than posting to a phone we cannot see.
     */
    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)

    /** What currently sits on id 1, so an unchanged state is not posted again. */
    private var lastLive: LiveSignature? = null

    override fun show(state: LiveState, now: TimeSnapshot) {
        val signature = LiveSignature.of(state)
        if (signature == lastLive) return
        lastLive = signature
        post(NotificationContract.ID_SESSION, factory.live(state, now))
    }

    override fun keepAlive(): Notification = factory.keepAlive(appCount = null)

    /**
     * §4.6: the completion post exists for the phone in his pocket. With the screen on the Focus
     * screen has already said it, so nothing is posted — and a session that lit no star (the focus cap
     * or the daily cap) is not announced either, because the only line it has would read "+0 stars".
     */
    override fun focusComplete(stars: Int) {
        // An unreadable power manager counts as a screen that is on: the Focus screen says it anyway.
        if (!shouldPostFocusComplete(stars, power?.isInteractive ?: true)) return
        post(NotificationContract.ID_ATTENTION, factory.focusComplete(stars) ?: return)
    }

    override fun asleep() {
        post(NotificationContract.ID_ATTENTION, factory.asleep())
    }

    /**
     * Posts without ever letting a notification problem reach the engine. Without notification
     * permission the service still runs and the timers still count; only the visible chip is missing,
     * which is the documented behaviour.
     */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post. Nothing else changes.
        }
    }

    /**
     * §4.6's rule for the completion post, as a truth table so it is pinned without Android.
     *
     * The post exists for the phone in his pocket: with the screen on, the Focus screen has already
     * said "Focus complete" and a shade post would announce the same fact twice. A session that lit
     * nothing — the focus cap or the daily cap — is not announced either, because the only line it
     * could carry would read "+0 stars" (§2.2).
     *
     * @param stars how many stars the session lit.
     * @param screenInteractive whether the screen is on right now.
     */
    internal companion object {
        fun shouldPostFocusComplete(stars: Int, screenInteractive: Boolean): Boolean =
            !screenInteractive && stars >= 1
    }

    /** The identity of a live notification, with the countdown rounded to ten seconds. */
    private data class LiveSignature(
        val kind: String,
        val packageName: String?,
        val totalMs: Long,
        val remainingBucket: Long,
        val explicitPause: Boolean,
        val sound: SoundKind?
    ) {
        companion object {
            private const val BUCKET_MS: Long = 10_000L

            fun of(state: LiveState): LiveSignature = when (state) {
                LiveState.None -> LiveSignature("none", null, 0L, 0L, false, null)
                is LiveState.GrantRunning -> LiveSignature(
                    kind = "grant",
                    packageName = state.packageName,
                    totalMs = state.grantedMs,
                    remainingBucket = bucket(state.remainingMs),
                    explicitPause = false,
                    sound = null
                )
                is LiveState.GrantHeld -> LiveSignature(
                    kind = "held",
                    packageName = null,
                    totalMs = 0L,
                    remainingBucket = bucket(state.remainingMs),
                    explicitPause = state.explicitPause,
                    sound = null
                )
                is LiveState.FocusRunning -> LiveSignature(
                    kind = "focus",
                    packageName = null,
                    totalMs = state.plannedMs,
                    remainingBucket = bucket(state.remainingMs),
                    explicitPause = false,
                    sound = state.sound
                )
            }

            private fun bucket(remainingMs: Long): Long = remainingMs.coerceAtLeast(0L) / BUCKET_MS
        }
    }
}
