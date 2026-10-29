package com.ramish.sadiq.guard

// CONTRACT-GAP: `HomeAction.openSadiq(route)` names a route, but nothing in the shell reads one —
// `MainActivity` (WP-01) starts the nav host on its start destination and takes no route argument.
// The smallest local seam: the route travels as the `EXTRA_ROUTE` extra on the `MainActivity` intent,
// which the shell can read when it lands. Until then Sadiq still comes to the front, which is what
// the self-test needs.

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.ramish.sadiq.MainActivity
import com.ramish.sadiq.contract.HomeAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the user home, or brings Sadiq itself to the front (§3.5).
 *
 * Home is pressed through the accessibility service when it is connected: `performGlobalAction` is
 * the only way an app can leave another app on every Android build, and it keeps working when the
 * system has denied the background-activity launch. The `CATEGORY_HOME` intent is the documented
 * fallback for the one case the service cannot cover — no service bound, which means no
 * interception either, but a surface that is already up still has to be able to dismiss itself.
 */
@Singleton
class HomeActionImpl @Inject constructor(
    private val handle: GuardServiceHandle,
    @ApplicationContext private val context: Context,
) : HomeAction {

    override fun goHome(): Boolean {
        if (handle.performHome()) return true
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            // A device with no launcher is not a device this app can help; the caller just sees false.
            false
        } catch (_: SecurityException) {
            // Background activity start denied by the ROM. Nothing else to try.
            false
        }
    }

    /**
     * Bring Sadiq to the front on [route].
     *
     * The activity is declared `singleTask`, so an existing task is reused and the intent arrives at
     * `onNewIntent` rather than stacking a second shell.
     */
    override fun openSadiq(route: String) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ROUTE, route)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The app's own activity cannot be missing; if it is, there is nothing to open.
        } catch (_: SecurityException) {
            // Same background-start restriction as above; the engine state is untouched either way.
        }
    }

    companion object {
        /** The route the shell should open, once the shell reads it. See the file header. */
        const val EXTRA_ROUTE: String = "com.ramish.sadiq.extra.ROUTE"
    }
}
