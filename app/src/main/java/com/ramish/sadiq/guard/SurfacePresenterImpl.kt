package com.ramish.sadiq.guard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ramish.sadiq.contract.MediaDucker
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SurfacePresenter
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.di.GuardWork
import com.ramish.sadiq.intercept.OverlayWindow
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
     * A refusal is logged inside the launcher rather than returned: the presenter has nothing
     * different to do about it, because the 600 ms visibility check is what turns any failure — a
     * thrown `SecurityException` or HyperOS dropping the launch silently — into a recorded miss
     * (and, while the overlay host is off, into the notification that explains it).
     */
    fun launch()
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

    override fun launch() {
        val from = handle.serviceContext() ?: context
        val intent = Intent()
            .setClassName(from.packageName, ACTIVITY)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        try {
            from.startActivity(intent)
        } catch (missing: ActivityNotFoundException) {
            Log.w(TAG, "intercept activity not found: ${missing.message}")
        } catch (denied: SecurityException) {
            // HyperOS refuses background activity starts unless "display pop-up windows" is allowed.
            // The 600 ms check in the presenter turns that into the recorded miss of A5-04.
            Log.w(TAG, "intercept activity refused: ${denied.message}")
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
 * not, and the overlay host is enabled, the presenter asks for the overlay and counts the fallback
 * for the day.
 *
 * With the overlay host off (AUDIT-4 A4-01) there is no second host at all, so a missed window is
 * the case AUDIT-5 A5-04 was about: the reel is ducked, no question appears, and nothing says why.
 * [missTheSurface] is that path — the duck is released, the miss is counted, and the first one of
 * the day is explained in the shade.
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
    private val mediaDucker: MediaDucker,
    private val misses: SurfaceMisses,
    private val notifier: SurfaceMissNotifier,
    @GuardWork private val scope: CoroutineScope,
) : SurfacePresenter {

    private var fallbackJob: Job? = null

    override fun show(ui: InterceptUi) {
        fallbackJob?.cancel()
        holder.show(ui)

        // When the overlay already holds the screen it re-renders the new question itself; starting
        // the activity on top of it would only make the two hosts race for the same surface.
        if (holder.overlayRequested.value) return

        launcher.launch()
        fallbackJob = scope.launch {
            delay(OVERLAY_FALLBACK_MS)
            // The activity won its window, or the question was answered before it appeared.
            if (holder.ui.value == null || holder.isVisible()) return@launch
            // There is no backup layer to ask for while the host is off (AUDIT-4 A4-01): asking would
            // leave `overlayRequested` true with nothing to draw it, and counting it would show him a
            // card promising a layer that does not exist.
            if (!OverlayWindow.HOST_ENABLED) {
                missTheSurface()
                return@launch
            }
            holder.requestOverlay()
            val now = clock.now()
            settings.bumpOverlayFallback(LogicalDays.of(now.wallMs, now.zoneId))
        }
    }

    override fun update(ui: InterceptUi) {
        // An update presumes a host is already drawing the surface (T17's check-in, T18's app chip).
        // After a process restart the engine restores `PROMPTING` from the snapshot while nothing is
        // on screen, so every later guarded open only emits `UpdateSurface` — and a surface written
        // into the holder that no host renders is the question silently never appearing. An update
        // no host can render is therefore a show.
        if (holder.isVisible() || holder.overlayRequested.value || fallbackJob?.isActive == true) {
            holder.update(ui)
        } else {
            show(ui)
        }
    }

    override fun dismiss() {
        fallbackJob?.cancel()
        fallbackJob = null
        holder.reportGone()
    }

    /**
     * The question was published, no host drew it, and the backup layer is off (AUDIT-5 A5-04).
     *
     * Three things happen, and each one is the difference between a silent miss and a visible one:
     *
     * - the media duck is released, so the reel the question paused plays again instead of staying
     *   silent under a question nobody can see;
     * - the miss is counted for the logical day, which is what puts "The question didn't appear" on
     *   the Sky while the condition lasts;
     * - the first miss of the day is explained once, in the shade, on the attention channel, with a
     *   tap that leads to the pop-ups setting.
     *
     * What is *not* done is as deliberate. `overlayRequested` stays false, because there is no host
     * to draw it. No overlay fallback is counted, because no fallback happened. And the surface is
     * left in the holder, so the next window change retries the launch rather than pretending the
     * question was answered.
     */
    private fun missTheSurface() {
        val now = clock.now()
        val missesToday = misses.record(LogicalDays.of(now.wallMs, now.zoneId))
        mediaDucker.release()
        Log.w(TAG, "missed: no host drew the surface, misses today=$missesToday")
        // One explanation a day: the shade is for the first refusal, and the card stays up until he
        // allows pop-ups or the day ends.
        if (missesToday == 1) notifier.questionCouldNotAppear()
    }

    companion object {
        /** How long the activity has to report itself visible before the overlay is requested. */
        const val OVERLAY_FALLBACK_MS: Long = 600L

        /** The one line per miss, in the same tag as the launcher that was refused. */
        private const val TAG = "SP/Intercept"
    }
}
