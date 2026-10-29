package com.ramish.sadiq.testing

import android.app.Notification
import com.ramish.sadiq.contract.LiveNotifier
import com.ramish.sadiq.core.engine.LiveState
import com.ramish.sadiq.core.time.TimeSnapshot

/**
 * A [LiveNotifier] that records what it was asked to show.
 *
 * The notification itself is built through [notificationFactory] so a JVM test never depends on
 * `android.app.Notification` behaving like the device; the default is an empty notification.
 *
 * @param notificationFactory builds the object returned by [keepAlive].
 */
class FakeLiveNotifier(
    private val notificationFactory: () -> Notification = { Notification() }
) : LiveNotifier {

    /** Every state passed to [show], in order. */
    val shown: MutableList<LiveState> = mutableListOf()

    /** Every star count passed to [focusComplete]. */
    val completed: MutableList<Int> = mutableListOf()

    /** How many times [keepAlive] was called. */
    var keepAliveCount: Int = 0
        private set

    /** How many times [asleep] was called. */
    var asleepCount: Int = 0
        private set

    override fun show(state: LiveState, now: TimeSnapshot) {
        shown += state
    }

    override fun keepAlive(): Notification {
        keepAliveCount += 1
        return notificationFactory()
    }

    override fun focusComplete(stars: Int) {
        completed += stars
    }

    override fun asleep() {
        asleepCount += 1
    }

    /** The last state shown, or null. */
    fun lastShown(): LiveState? = shown.lastOrNull()
}
