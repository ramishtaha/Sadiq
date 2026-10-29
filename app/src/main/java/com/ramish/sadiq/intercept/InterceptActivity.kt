package com.ramish.sadiq.intercept

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.guard.InterceptStateHolder
import com.ramish.sadiq.ui.components.AppIcon
import com.ramish.sadiq.ui.components.LocalAppIconLoader
import com.ramish.sadiq.ui.intercept.InterceptCopy
import com.ramish.sadiq.ui.intercept.InterceptScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The full-screen question, launched from the accessibility service.
 *
 * It renders exactly the same [InterceptScreen] the overlay host renders, so there is one surface
 * implementation and no chance of the two drifting apart. It decides nothing: a choice goes back to
 * the controller as a [GuardInput.Chose], and the reducer decides what happens next.
 *
 * It also leaves. Three things make that certain, because a question that stays on screen with
 * nothing to say is a blank dark window over the app the user was in:
 *
 * - When the engine stops prompting — answered, the app changed, the screen went off, a grant
 *   started — the activity finishes and removes its task.
 * - When the surface itself goes away, the activity finishes too.
 * - If a launch ever arrives with nothing to render, a short grace period ends it rather than
 *   leaving the window up.
 *
 * Every answer is submitted at most once, whichever of the pills, the chips or Back produced it.
 */
@AndroidEntryPoint
class InterceptActivity : ComponentActivity() {

    @Inject
    lateinit var holder: InterceptStateHolder

    @Inject
    lateinit var interceptCopy: InterceptCopy

    @Inject
    lateinit var guardController: GuardController

    @Inject
    lateinit var installedAppsSource: InstalledAppsSource

    /** The last answer sent, and when, so a double tap or a chip-plus-pill is one input. */
    private var lastAnswer: Choice? = null
    private var lastAnswerAtMs: Long = 0L

    /** Whether this activity has begun its own finish, so it is never asked twice. */
    private var closing = false

    /** Whether a surface has ever been rendered; guards the blank-window timeout. */
    private var sawSurface = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page colour behind the surface, so the first frame never flashes white over a dark app.
        window.setBackgroundDrawableResource(R.color.page_dark)

        Log.i(TAG, "activity created")

        setContent {
            val state by holder.ui.collectAsStateWithLifecycle()
            val ui = state
            // The engine keeps the spent grant until the surface is answered, so this is the length
            // TIME_UP names. Collected separately so a new grant does not recompose on every tick.
            val grantedMs by guardController.state
                .map { it.grant?.grantedMs }
                .collectAsStateWithLifecycle(initialValue = guardController.state.value.grant?.grantedMs)

            if (ui == null) {
                // One frame of "nothing yet" is normal: the holder publishes just before the launch.
                // Never close from here — closing during composition would race the publish; the
                // collectors in onCreate end the activity instead.
                return@setContent
            }

            val appLabel = appLabelFor(ui.packageName)

            CompositionLocalProvider(LocalAppIconLoader provides installedAppsSource::icon) {
                InterceptScreen(
                    ui = ui,
                    onChoice = ::answer,
                    appLabel = appLabel,
                    appIcon = { AppIcon(packageName = ui.packageName, size = 20.dp) },
                    interceptCopy = interceptCopy,
                    grantedMinutes = grantedMs?.let { (it / EngineRules.MINUTE_MS).toInt() }
                )
            }
        }

        // The question is over the moment the engine leaves PROMPTING: answered, the app changed,
        // the screen went off, a grant started, the app stopped being guarded. Any of those must
        // take this window with it.
        lifecycleScope.launch {
            guardController.state.collect { state ->
                // No prompt, or not prompting any more: there is nothing for this window to ask.
                if (state.phase != Phase.PROMPTING || state.prompt == null) close()
            }
        }

        // Belt and braces: if the surface itself is withdrawn while the engine still thinks it is
        // prompting, there is nothing left to draw.
        lifecycleScope.launch {
            holder.ui.collect { ui ->
                if (ui != null) {
                    sawSurface = true
                } else if (guardController.state.value.phase != Phase.PROMPTING) {
                    close()
                }
            }
        }

        // A launch that never produced a surface (a race with a dismiss, or a restored task) must
        // not leave a dark window in front of the user.
        lifecycleScope.launch {
            delay(NO_SURFACE_TIMEOUT_MS)
            if (!sawSurface) close()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        // The activity always reports itself: it is the primary host (§3.6), and a report is also how
        // a late activity takes a question back from the fallback. Gating this on `overlayRequested`
        // meant a slow activity never reported at all, so the surface kept an overlay request nobody
        // could satisfy and no latency was ever recorded.
        holder.reportVisible(viaOverlay = false)
    }

    /**
     * Sends one answer to the engine, at most once.
     *
     * The screen can produce the same answer twice — a double tap, Back during the leave
     * confirmation, a chip and the pill above it — and a second [GuardInput.Chose] for a surface
     * the engine has already answered is a second input, not a second decision. Identical answers
     * inside [DUPLICATE_WINDOW_MS] are dropped here. A *different* answer is always let through:
     * if the engine refused the first one, the surface is still up and must stay answerable.
     */
    private fun answer(choice: Choice) {
        val now = SystemClock.elapsedRealtime()
        if (choice == lastAnswer && now - lastAnswerAtMs < DUPLICATE_WINDOW_MS) {
            Log.i(TAG, "duplicate answer ignored: $choice")
            return
        }
        lastAnswer = choice
        lastAnswerAtMs = now
        Log.i(TAG, "answer: $choice")
        guardController.submit(GuardInput.Chose(choice))
    }

    /** Ends this activity and its task, once. */
    private fun close() {
        if (closing) return
        closing = true
        Log.i(TAG, "surface gone, finishing")
        if (!isFinishing && !isDestroyed) finishAndRemoveTask()
    }

    override fun onPause() {
        super.onPause()
        // Covered or left: the surface is not on screen any more. Keeping the latch would make the
        // next guarded open emit UpdateSurface against a host nobody can see.
        holder.reportHidden()
    }

    /** The app's own label, or the package name when the label cannot be read. */
    private fun appLabelFor(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    companion object {
        private const val TAG = "SP/Intercept"

        /** How long a launch with no surface may stay up before it closes itself. */
        private const val NO_SURFACE_TIMEOUT_MS: Long = 10_000L

        /** Identical answers closer together than this are one answer. */
        private const val DUPLICATE_WINDOW_MS: Long = 1_000L

        /** The intent the presenter uses to launch this activity. */
        fun createIntent(context: Context): Intent = Intent(context, InterceptActivity::class.java)
    }
}
