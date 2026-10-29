package com.ramish.sadiq.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
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

    /** What currently sits on id 1, so an unchanged state is not posted again. */
    private var lastLive: LiveSignature? = null

    override fun show(state: LiveState, now: TimeSnapshot) {
        val signature = LiveSignature.of(state)
        if (signature == lastLive) return
        lastLive = signature
        post(NotificationContract.ID_SESSION, factory.live(state, now))
    }

    override fun keepAlive(): Notification = factory.keepAlive(appCount = null)

    override fun focusComplete(stars: Int) {
        post(NotificationContract.ID_ATTENTION, factory.focusComplete(stars))
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
