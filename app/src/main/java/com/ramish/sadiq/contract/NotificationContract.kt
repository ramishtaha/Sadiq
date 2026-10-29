package com.ramish.sadiq.contract

/**
 * The notification ids, actions and receiver name, in one place.
 *
 * The actions are the strings the session service's `PendingIntent`s carry and the receiver matches.
 * They are part of the app's external surface: a notification posted by an older build must still be
 * answerable after an update, so these values never change.
 */
object NotificationContract {
    /** The single ongoing notification: keep-alive, grant timer, focus timer, Live Update. */
    const val ID_SESSION = 1

    /** The "Sadiq is asleep" attention notification, at most one per 24 h. */
    const val ID_ATTENTION = 2

    /** "I'm done": close the grant early and light the star. */
    const val ACTION_DONE_EARLY = "com.ramish.sadiq.DONE_EARLY"

    /** "Save the rest": keep the remaining minutes for later. */
    const val ACTION_PAUSE = "com.ramish.sadiq.PAUSE"

    /** "Let it go": drop a saved grant. */
    const val ACTION_DISCARD = "com.ramish.sadiq.DISCARD"

    /** End the running focus session. */
    const val ACTION_END_FOCUS = "com.ramish.sadiq.END_FOCUS"

    /** Toggle the focus soundscape from the notification. */
    const val ACTION_TOGGLE_SOUND = "com.ramish.sadiq.TOGGLE_SOUND"

    /** The receiver that turns the actions above into `GuardInput`s. Declared in the manifest. */
    const val RECEIVER = "com.ramish.sadiq.session.NotificationActionReceiver"
}
