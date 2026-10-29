package com.ramish.sadiq.intercept

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ramish.sadiq.AppInitializer
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.core.engine.Choice
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.di.GuardWork
import com.ramish.sadiq.guard.GuardServiceHandle
import com.ramish.sadiq.guard.InterceptStateHolder
import com.ramish.sadiq.ui.components.AppIcon
import com.ramish.sadiq.ui.components.LocalAppIconLoader
import com.ramish.sadiq.ui.intercept.InterceptCopy
import com.ramish.sadiq.ui.intercept.InterceptScreen
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The question, when the activity could not show it.
 *
 * This is the second host of `InterceptScreen`, and it draws the *same composable* the activity
 * draws, with the same [InterceptCopy] and the same answers (AUDIT-3 G-13). That is the whole point:
 * what the user sees and what a tap means cannot drift between the two, because there is only one
 * surface implementation.
 *
 * **When it appears.** Only when the presenter has asked for it: `InterceptStateHolder.show`
 * publishes the surface, launches `InterceptActivity`, and 600 ms later — if the activity never
 * reported itself visible, which is what an OEM that drops background activity starts looks like —
 * sets `overlayRequested`. A device where the activity works never sees this window. That is
 * deliberate (§3.6): the overlay is the fallback, not the default.
 *
 * **When it goes away**, all of them through [OverlayPolicy] and none of them silently:
 *
 * - the question is answered, so the engine leaves `PROMPTING` and the presenter dismisses the
 *   surface ([InterceptStateHolder.ui] goes null);
 * - the engine leaves `PROMPTING` for any other reason — the app changed, a grant started, the
 *   config changed;
 * - the screen went off: the service submits `ScreenOff`, the engine answers it with T08 and
 *   `DismissSurface`, which is the same path the activity takes;
 * - the accessibility service is unbound or destroyed, checked through [GuardServiceHandle.connected]
 *   because the window may only exist while the service does.
 *
 * **Never a leak.** The window is added and removed on the main thread only, always through
 * [OverlayWindow], whose `removeViewImmediate` is wrapped: a removal that fails is logged, never
 * thrown. A watchdog re-checks an unchanged window every five minutes and takes it down if the
 * state says the question is over — it never removes a question the engine is still asking, because
 * an unanswered question disappearing is exactly the failure this class exists to fix.
 *
 * Every failure is logged under `SP/Overlay` with the reason and changes nothing else: if the window
 * cannot be added, the app behaves exactly as it did before the overlay existed.
 */
@Singleton
class OverlayHost @Inject constructor(
    private val holder: InterceptStateHolder,
    private val controller: GuardController,
    private val copy: InterceptCopy,
    private val icons: InstalledAppsSource,
    private val handle: GuardServiceHandle,
    @GuardWork private val scope: CoroutineScope,
) : AppInitializer {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The surface the composition draws.
     *
     * It is read inside `setContent`, so writing it recomposes the window in place. A second
     * question arriving while the overlay is up (T17's check-in, T18's app chip) is therefore one
     * write, not a remove-and-add that would flicker through the app underneath.
     */
    private val surface = mutableStateOf<InterceptUi?>(null)

    /** The window this host owns, if any. Main thread only. */
    private var window: OverlayWindow? = null

    /** The lifecycle of the composition inside the window. Main thread only. */
    private var owner: OverlayLifecycleOwner? = null

    private var started = false

    /** The last answer sent, and when, so a double tap is one input — the activity's rule. */
    private var lastAnswer: Choice? = null
    private var lastAnswerAtMs = 0L

    /** Whether the single retry after a failed add has been spent for this request. */
    private var retried = false

    /**
     * Subscribes to the four things that can change the answer to "should this be on screen".
     *
     * An initializer rather than a call from the service, so the subscription exists before the
     * first question of the process (§3.5). Every emission is one post to the main thread, where the
     * window is actually touched; concurrent emissions collapse into one [apply], which reads the
     * latest values itself.
     */
    override fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                holder.ui,
                holder.overlayRequested,
                controller.state,
                handle.connected,
            ) { _, _, _, _ -> Unit }.collect { handler.post { apply() } }
        }
    }

    /**
     * Brings the window in line with the state. Main thread only, and idempotent: every path that
     * changes anything ends in this one function.
     *
     * The whole body is guarded because it runs on the main thread, posted from a flow: an escape
     * from here would take the process down, and the process is the accessibility service. A failure
     * is one line under `SP/Overlay` and the app keeps running unguarded-but-alive, which is the only
     * honest thing left when the fallback itself cannot draw.
     */
    private fun apply() {
        // OFF until it survives a device (2026-10-30, 04:45): on the emulator the first real
        // question drew this window and the process then ANR'd in SessionService ("waited 20089ms")
        // with a 109 s eventToVisible — the service window's first composition blocks the same main
        // thread the session service needs. An ANR on every question is worse than the OEM gap it
        // closes, so the activity stays the only host until the composition is moved off the
        // critical path. The code and its tests stay here, wired and ready, behind this flag.
        if (!ENABLED) return
        try {
            val ui = holder.ui.value
            val state = controller.state.value
            val action = OverlayPolicy.decide(
                hasSurface = ui != null,
                requested = holder.overlayRequested.value,
                prompting = state.phase == Phase.PROMPTING && state.prompt != null,
                serviceBound = handle.connected.value,
                showing = window?.isShowing == true,
            )
            when (action) {
                OverlayPolicy.Action.SHOW -> if (ui != null) show(ui)
                OverlayPolicy.Action.UPDATE -> if (ui != null) render(ui)
                OverlayPolicy.Action.REMOVE -> hide(reasonFor(ui, state.phase, state.prompt != null))
                OverlayPolicy.Action.NONE -> Unit
            }
            armWatchdog()
        } catch (failure: Exception) {
            Log.w(TAG, "the window step failed: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
    }

    /**
     * Adds the window and puts the surface in it.
     *
     * The window manager comes from the bound service, never from the application context: only the
     * service's own `WindowManager` may add a `TYPE_ACCESSIBILITY_OVERLAY` window, and asking the
     * application context for one would be a `SecurityException` at best.
     */
    private fun show(ui: InterceptUi) {
        val service = handle.serviceContext()
        val windowManager = handle.windowManager()
        if (service == null || windowManager == null) {
            // The plan's own answer for this case: with no service there is nothing to cover with.
            Log.w(TAG, "not shown: the accessibility service is not bound")
            return
        }

        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.create()
        lifecycleOwner.onBackPressedDispatcher.addCallback(
            lifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    answer(Choice.Leave)
                }
            },
        )

        surface.value = ui
        val content = createContent(service, lifecycleOwner)
        val candidate = OverlayWindow(windowManager)
        if (!candidate.show(content)) {
            // Nothing was attached: drop the composition's owners and keep the app exactly as it
            // was before the fallback existed.
            lifecycleOwner.destroy()
            surface.value = null
            retryOnce()
            return
        }

        window = candidate
        owner = lifecycleOwner
        // From STARTED upwards only: an owner left at CREATED draws no frames at all, which is the
        // classic blank-overlay failure and the one thing a device would never tell us.
        lifecycleOwner.resume()
        retried = false
        // The holder's answer to "is a host really on screen": settles the pending request, records
        // the latency as `via=overlay`, and makes the next surface redraw this window instead of
        // launching the activity over it.
        holder.reportVisible(viaOverlay = true)
        Log.i(TAG, "shown: ${ui.kind} ${ui.packageName}")
    }

    /** Redraws the surface that is already up. Ignored when it is the same value. */
    private fun render(ui: InterceptUi) {
        if (surface.value == ui) return
        surface.value = ui
    }

    /**
     * Takes the window down.
     *
     * The view goes first and its owners second: `DESTROYED` disposes the composition, and disposing
     * a composition whose view is still attached would draw one more frame into a dead window.
     */
    private fun hide(reason: String) {
        val current = window ?: return
        window = null
        current.hide()
        owner?.destroy()
        owner = null
        surface.value = null
        // The overlay no longer owns the screen. Clearing the latch is what makes the next question
        // start with a fresh 600 ms race instead of inheriting this one's report — the same thing
        // `InterceptActivity.onPause` does for the activity host.
        holder.reportHidden()
        Log.i(TAG, "removed: $reason")
    }

    /**
     * Builds the window's content: the app's page colour, and the same screen the activity shows.
     *
     * The `ComposeView` gets all four owners it needs. The three view-tree owners are how Compose
     * finds a lifecycle at all; the two composition locals are what `InterceptScreen`'s `BackHandler`
     * and any lifecycle-aware state read — a service is not an `OnBackPressedDispatcherOwner`, so
     * without them the composition would fail on the first question.
     */
    private fun createContent(
        service: Context,
        lifecycleOwner: OverlayLifecycleOwner,
    ): ViewGroup {
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val ui = surface.value ?: return@setContent
                val label = remember(ui.packageName) { appLabelFor(service, ui.packageName) }
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycleOwner,
                    LocalOnBackPressedDispatcherOwner provides lifecycleOwner,
                    LocalAppIconLoader provides icons::icon,
                ) {
                    InterceptScreen(
                        ui = ui,
                        onChoice = ::answer,
                        appLabel = label,
                        appIcon = { AppIcon(packageName = ui.packageName, size = 20.dp) },
                        interceptCopy = copy,
                        grantedMinutes = grantedMinutes(),
                    )
                }
            }
        }

        return OverlayRootView(service, onBack = { answer(Choice.Leave) }).apply {
            // The same first-frame colour as `Theme.Sadiq.Intercept`: a transparent window over
            // Instagram reads as a flicker, and a white one reads as a different app.
            setBackgroundColor(service.getColor(R.color.page_dark))
            // Owners belong on the root, not only on the ComposeView: the decor that `addView`
            // creates asks the view it is given for a lifecycle, and a window whose root has none
            // throws "ViewTreeLifecycleOwner not found" on the first frame — inside the service's
            // process, which kills the app and the guard with it.
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /**
     * Sends one answer to the engine, at most once.
     *
     * The activity's rule, kept identical: the same choice within a second is one tap that arrived
     * twice. A *different* choice always goes through — if the engine refused the first one, the
     * surface is still up and has to stay answerable.
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
        controller.submit(GuardInput.Chose(choice))
    }

    /**
     * TIME_UP only: the length of the grant that ran out, read at render time.
     *
     * The activity collects the same value from the controller; here it is read once per
     * composition, and the window recomposes on every new surface, so a grant that changed under the
     * same surface cannot be missed.
     */
    private fun grantedMinutes(): Int? =
        controller.state.value.grant?.grantedMs?.let { (it / EngineRules.MINUTE_MS).toInt() }

    /**
     * One more attempt after the window could not be added.
     *
     * `BadTokenException` happens while a fresh service's window session is still settling, and the
     * same call half a second later succeeds. It is a single retry, never a loop: if that one also
     * fails, the presenter has already recorded the fallback and the app behaves as it did before.
     */
    private fun retryOnce() {
        if (retried) return
        retried = true
        handler.postDelayed({ apply() }, RETRY_DELAY_MS)
    }

    /**
     * Keeps a slow re-check armed while a window is up.
     *
     * It exists to catch a window whose flows stopped reporting (a process that was frozen mid
     * question, a `removeView` the platform refused): five minutes later the state is read again and
     * the window is taken down if the question is over. It never removes a live question.
     */
    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        if (window != null) handler.postDelayed(watchdog, WATCHDOG_DELAY_MS)
    }

    private val watchdog = Runnable {
        Log.i(TAG, "watchdog: re-checking an unchanged window")
        apply()
    }

    /** Why a window is being taken down, for the log line that has to make sense in the field. */
    private fun reasonFor(ui: InterceptUi?, phase: Phase, hasPrompt: Boolean): String = when {
        !handle.connected.value -> "the accessibility service is gone"
        phase != Phase.PROMPTING || !hasPrompt -> "the engine is no longer asking"
        ui == null -> "the surface was withdrawn"
        else -> "the overlay was not requested"
    }

    /** The app's own label, or the package name when the label cannot be read. */
    private fun appLabelFor(context: Context, packageName: String): String = try {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
            .toString()
    } catch (_: Exception) {
        packageName
    }

    private companion object {
        const val TAG: String = OverlayWindow.TAG

        /**
         * Whether the fallback may draw at all.
         *
         * False because the first device run showed the window draws and then the process ANRs in
         * `SessionService` (the service's 20 s exec timeout) with a 109 s `eventToVisible`. Nothing
         * else in the app is allowed to be a reason the question is late, so this stays false until
         * the composition is off the main thread's critical path and a device run is clean.
         */
        const val ENABLED: Boolean = OverlayWindow.HOST_ENABLED

        /** Identical answers closer together than this are one answer. */
        const val DUPLICATE_WINDOW_MS: Long = 1_000L

        /** How long to wait before the single retry after a refused `addView`. */
        const val RETRY_DELAY_MS: Long = 500L

        /** How long an unchanged window may go unchecked. */
        const val WATCHDOG_DELAY_MS: Long = 5L * 60L * 1_000L
    }
}
