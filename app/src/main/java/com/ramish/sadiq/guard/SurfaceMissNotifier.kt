package com.ramish.sadiq.guard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.ramish.sadiq.R
import com.ramish.sadiq.notify.Channels
import com.ramish.sadiq.system.IntentCandidates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user that a question could not be put on screen (AUDIT-5 A5-04).
 *
 * The seam exists so [SurfacePresenterImpl] stays free of Android types: the presenter decides
 * *when* a miss is worth a notification, and this decides how it looks and where it leads.
 */
interface SurfaceMissNotifier {

    /**
     * Post the one notification that explains a missed question.
     *
     * Called at most once per logical day, on the first miss, so the attention channel stays the
     * rare thing it is described as.
     */
    fun questionCouldNotAppear()
}

/**
 * The attention-channel post for a missed question, deep-linked to the pop-ups setting.
 *
 * The channel is [Channels.ATTENTION], the app's one "you need to know this" channel: it is the
 * loudest tier Sadiq has (it peeks; nothing in Sadiq plays a sound), so no priority flag is set —
 * on API 26+ the channel's importance is the only thing the platform reads.
 *
 * The tap opens the page the fix lives on, not the app: the user is in another app when this
 * arrives, and "Open Sadiq" would leave him to find Sadiq → Settings → Health himself. On HyperOS
 * that is the per-app permission editor; on a build without it, the system's own pop-up-windows page;
 * on a build with neither, Sadiq itself rather than a notification that does nothing.
 */
@Singleton
class AttentionSurfaceMissNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SurfaceMissNotifier {

    /**
     * Posts without ever letting a notification problem reach the presenter: without notification
     * permission the guard still runs, only the explanation is absent, which is the documented
     * behaviour for every other post in Sadiq.
     */
    @SuppressLint("MissingPermission")
    override fun questionCouldNotAppear() {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val builder = Notification.Builder(context, Channels.ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.notify_surface_missed_title))
            .setContentText(context.getString(R.string.notify_surface_missed_text))
        popupsPage()?.let { page ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    RC_POPUPS,
                    page,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        try {
            manager.notify(ID_SURFACE_MISS, builder.build())
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post. Nothing else changes.
        }
    }

    /**
     * The closest page to the pop-ups permission this build can open, or null when it has none.
     *
     * The three are the same three the wizard's O7 step walks, minus the app-info fallback: app info
     * is where the user already was, and the notification exists to take him somewhere new.
     */
    private fun popupsPage(): Intent? {
        val editor = Intent(IntentCandidates.ACTION_MIUI_APP_PERM_EDITOR)
            .setComponent(
                ComponentName(
                    IntentCandidates.MIUI_SECURITY_CENTER,
                    IntentCandidates.MIUI_PERMISSIONS_EDITOR,
                )
            )
            .putExtra(IntentCandidates.EXTRA_MIUI_PKG_NAME, context.packageName)
        val overlay = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        val page = listOf(editor, overlay).firstOrNull { it.canBeOpened() }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return page
    }

    /**
     * Whether an activity would answer [this] intent.
     *
     * A null here is also what a package-visibility filter looks like, so a false negative costs the
     * next page in the chain, never a dead notification: the last entry is Sadiq's own launcher
     * intent, which always resolves.
     */
    private fun Intent.canBeOpened(): Boolean = resolveActivity(context.packageManager) != null

    private companion object {
        /**
         * A third id: [com.ramish.sadiq.contract.NotificationContract] reserves 1 for the session and
         * 2 for the attention slot the asleep post uses, and a miss must not overwrite either.
         */
        const val ID_SURFACE_MISS: Int = 3

        const val RC_POPUPS: Int = 20
    }
}
