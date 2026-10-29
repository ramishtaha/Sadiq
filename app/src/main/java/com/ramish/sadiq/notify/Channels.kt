package com.ramish.sadiq.notify

import androidx.annotation.StringRes
import com.ramish.sadiq.R

/**
 * How loud a channel is allowed to be, in Sadiq's own terms.
 *
 * The enum exists so [ChannelSpec] can be described and tested without Android; [NotificationInitializer]
 * is the one place that maps it to `NotificationManager.IMPORTANCE_*`.
 */
enum class ChannelImportance {
    /** No status-bar icon, no sound, no peek: the keep-alive line. */
    MIN,

    /** Silent but visible: the countdown while a grant or a focus session runs. */
    LOW,

    /** Silent but allowed to peek: the rare things the user must know about. */
    DEFAULT
}

/**
 * A notification channel exactly as §4.6 defines it.
 *
 * Every channel is silent, still and badgeless on purpose: a badge would be one more number to check,
 * and Sadiq exists to remove those. The fields stay explicit (rather than relying on platform
 * defaults) so [ChannelSpecTest] can hold the whole table in place.
 *
 * @property id the channel id; user-visible state, so it never changes.
 * @property importance how loud the channel is.
 * @property nameRes the channel name shown in system settings.
 * @property descriptionRes the one-line explanation shown under the name.
 * @property showBadge whether the launcher icon may show a dot; never true in Sadiq.
 * @property playSound whether a post may make a sound; never true in Sadiq.
 * @property vibrate whether a post may vibrate; never true in Sadiq.
 */
data class ChannelSpec(
    val id: String,
    val importance: ChannelImportance,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val showBadge: Boolean = false,
    val playSound: Boolean = false,
    val vibrate: Boolean = false
)

/**
 * The three channels Sadiq creates at startup (§4.6). Ids carry no brand and are unchanged from the
 * original plan; only the display names are new (name addendum §2.5).
 */
object Channels {
    /** Keep-alive: "Sadiq is on" · "Guarding 5 apps". Tap → Sky. No actions. */
    const val GUARD_STATUS: String = "guard_status"

    /** Grant and focus Live Updates, sharing notification id 1 with the foreground service. */
    const val SESSION_LIVE: String = "session_live"

    /** The rare attention posts: asleep, focus complete, weekly review. */
    const val ATTENTION: String = "attention"

    /** The keep-alive channel the foreground service posts on. */
    val guardStatus: ChannelSpec = ChannelSpec(
        id = GUARD_STATUS,
        importance = ChannelImportance.MIN,
        nameRes = R.string.notify_channel_guard_status,
        descriptionRes = R.string.notify_channel_guard_status_description
    )

    /** The channel of the countdown chip. */
    val sessionLive: ChannelSpec = ChannelSpec(
        id = SESSION_LIVE,
        importance = ChannelImportance.LOW,
        nameRes = R.string.notify_channel_session_live,
        descriptionRes = R.string.notify_channel_session_live_description
    )

    /** The channel of everything that needs a moment of the user's attention. */
    val attention: ChannelSpec = ChannelSpec(
        id = ATTENTION,
        importance = ChannelImportance.DEFAULT,
        nameRes = R.string.notify_channel_attention,
        descriptionRes = R.string.notify_channel_attention_description
    )

    /** Every channel, in the order the spec lists them. */
    val all: List<ChannelSpec> = listOf(guardStatus, sessionLive, attention)
}
