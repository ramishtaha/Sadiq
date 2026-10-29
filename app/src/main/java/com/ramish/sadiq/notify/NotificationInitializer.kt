package com.ramish.sadiq.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.ramish.sadiq.AppInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Creates Sadiq's three channels at process start (§4.6), so the foreground service can post before
 * any screen is drawn.
 *
 * Creating a channel is idempotent: the platform updates the name and description of an existing
 * channel and leaves the user's own choices (importance, sound) alone, so running this on every start
 * is both safe and the only way to keep the settings text current.
 */
class NotificationInitializer @Inject constructor(
    @ApplicationContext private val context: Context
) : AppInitializer {

    override fun start() {
        createChannels(context)
    }

    companion object {
        /** Visible so a settings deep link or a test can create the same channels without Hilt. */
        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            Channels.all.forEach { spec ->
                manager.createNotificationChannel(spec.toChannel(context))
            }
        }
    }
}

/**
 * The single place where a [ChannelSpec] becomes a platform channel. Every silence rule is applied
 * explicitly rather than inherited from a platform default, which is the point of the spec type.
 */
private fun ChannelSpec.toChannel(context: Context): NotificationChannel =
    NotificationChannel(id, context.getString(nameRes), importance.toPlatformImportance()).apply {
        description = context.getString(descriptionRes)
        setShowBadge(showBadge)
        setSound(if (playSound) Settings.System.DEFAULT_NOTIFICATION_URI else null, null)
        enableVibration(vibrate)
        enableLights(false)
        setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)
    }

/** Sadiq's own vocabulary mapped onto the platform's; kept out of [ChannelSpec] so it stays pure. */
private fun ChannelImportance.toPlatformImportance(): Int = when (this) {
    ChannelImportance.MIN -> NotificationManager.IMPORTANCE_MIN
    ChannelImportance.LOW -> NotificationManager.IMPORTANCE_LOW
    ChannelImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
}
