package com.ramish.sadiq.guard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SurfacePresenter
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.di.GuardWork
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts the interception activity. The seam exists so [SurfacePresenterImpl] can be tested without
 * an Android `Context`, and so the decision of *which* context to launch from stays in one place.
 */
interface InterceptLauncher {
    /**
     * Start `InterceptActivity`.
     *
     * @return true when the OS accepted the launch, false when it was silently refused (which is
     *   the HyperOS background-popup case the overlay exists for).
     */
    fun launch(): Boolean
}

/**
 * Launches the interception activity from the accessibility service when it is connected, otherwise
 * from the application context (§3.5).
 *
 * The class is named by string, not by `::class.java`, so this file does not depend on WP-15's
 * source: if the activity is missing from a build, the intent fails at runtime and the overlay
 * fallback covers it, instead of the whole module failing to compile.
 */
@Singleton
class ContextInterceptLauncher @Inject constructor(
    private val handle: GuardServiceHandle,
    @ApplicationContext private val context: Context,
) : InterceptLauncher {

    override fun launch(): Boolean {
        val from = handle.serviceContext() ?: context
        val intent = Intent()
            .setClassName(from.packageName, ACTIVITY)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        return try {
            from.startActivity(intent)
            true
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "intercept activity not found: ${missing.message}")
            false
        } catch (denied: SecurityException) {
            // HyperOS refuses background activity starts unless "display pop-up windows" is allowed.
            // The 600 ms check in the presenter turns that into the overlay fallback.
            Log.w(TAG, "intercept activity refused: ${denied.message}")
            false
        }
    }

    private companion object {
        const val TAG = "SP/Intercept"

        /** The interception activity declared by WP-01's manifest and implemented by WP-15. */
        const val ACTIVITY = "com.ramish.sadiq.intercept.InterceptActivity"
    }
}

/**
 * Puts the question on screen, and takes it away (§3.6).
 *
 * Two hosts can show it: `InterceptActivity`, which is a real activity and therefore the fast path,
 * and an accessibility overlay, which needs no background-activity permission. The presenter always
 * tries the activity first and gives it [OVERLAY_FALLBACK_MS] to report itself visible. If it does
 * not — the launch was refused, or the activity never resumed — the presenter requests the overlay
 * and counts the fallback for the day, which is what feeds the "Allow pop-ups for faster pauses"
 * card after three of them.
 *
 * The surface is published to [InterceptStateHolder] *before* the launch, so the activity can render
 * on its first frame instead of showing a loading state (§4.4).
 */
@Singleton
class SurfacePresenterImpl @Inject constructor(
    private val holder: InterceptStateHolder,
    private val launcher: InterceptLauncher,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @GuardWork private val scope: CoroutineScope,
) : SurfacePresenter {

    private var fallbackJob: Job? = null

    override fun show(ui: InterceptUi) {
        fallbackJob?.cancel()
        holder.show(ui)
        launcher.launch()
        fallbackJob = scope.launch {
            delay(OVERLAY_FALLBACK_MS)
            // The activity won its window, or the question was answered before it appeared.
            if (holder.ui.value == null || holder.isVisible()) return@launch
            holder.requestOverlay()
            val now = clock.now()
            settings.bumpOverlayFallback(LogicalDays.of(now.wallMs, now.zoneId))
        }
    }

    override fun update(ui: InterceptUi) {
        holder.update(ui)
    }

    override fun dismiss() {
        fallbackJob?.cancel()
        fallbackJob = null
        holder.reportGone()
    }

    companion object {
        /** How long the activity has to report itself visible before the overlay is requested. */
        const val OVERLAY_FALLBACK_MS: Long = 600L
    }
}
