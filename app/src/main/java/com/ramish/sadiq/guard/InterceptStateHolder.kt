package com.ramish.sadiq.guard

import android.util.Log
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single value both interception hosts render.
 *
 * [com.ramish.sadiq.intercept.InterceptActivity] and the accessibility overlay read [ui] and nothing
 * else, so the two can never disagree about what the question is, and the UI never has to read
 * [com.ramish.sadiq.core.engine.EngineState] to decide what to draw.
 *
 * This is also where the 600 ms visibility race is decided (§3.6): [SurfacePresenterImpl] arms a
 * timer when it publishes a surface, the host calls [reportVisible] once it is actually on screen,
 * and [lastLatencyMs] records how long that took. When the host loses the race, [overlayRequested]
 * turns true and the overlay fallback takes over.
 */
@Singleton
class InterceptStateHolder @Inject constructor(
    private val clock: Clock,
) {

    private val mutableUi = MutableStateFlow<InterceptUi?>(null)

    /** The surface to render, or null when no question is up. */
    val ui: StateFlow<InterceptUi?> = mutableUi.asStateFlow()

    private val mutableOverlayRequested = MutableStateFlow(false)

    /** Whether the overlay fallback should be on screen. */
    val overlayRequested: StateFlow<Boolean> = mutableOverlayRequested.asStateFlow()

    private val mutableLastLatencyMs = MutableStateFlow<Long?>(null)

    /** How long the last surface took to become visible, or null when none has been reported yet. */
    val lastLatencyMs: StateFlow<Long?> = mutableLastLatencyMs.asStateFlow()

    /** The elapsed reading at which [ui] was published; null between surfaces. */
    private var shownAtElapsedMs: Long? = null

    /**
     * Whether a host has reported the current surface visible.
     *
     * Written on the main thread by the activity and read by the presenter's fallback coroutine, so
     * it is volatile rather than a second flow: it changes at most twice per surface.
     */
    @Volatile
    private var visible: Boolean = false

    /** Whether a host has confirmed the current surface is on screen. */
    fun isVisible(): Boolean = visible

    /**
     * Publish a new surface and restart its visibility clock.
     *
     * A question that arrives while a host is already showing one keeps that host: the overlay or
     * activity re-renders from the new value, so the pending overlay request (if any) is left alone
     * and the surface does not flicker through a remove-and-re-add. When nothing is visible, the
     * question starts with no overlay request and has to prove itself again.
     *
     * @param ui the surface to render.
     */
    fun show(ui: InterceptUi) {
        shownAtElapsedMs = clock.now().elapsedMs
        if (!visible) mutableOverlayRequested.value = false
        mutableUi.value = ui
    }

    /**
     * Redraw the surface that is already up without restarting its clock.
     *
     * T17 answers a check-in without leaving the surface, and T18 switches the app chip; neither is
     * a new question, so the decision time and the latency measurement keep their origin.
     *
     * @param ui the surface to render now.
     */
    fun update(ui: InterceptUi) {
        mutableUi.value = ui
    }

    /**
     * A host is showing the surface.
     *
     * @param viaOverlay true when the overlay host reported it, false for the activity. An activity
     *   that wins the race cancels a pending overlay request, because the two hosts must never both
     *   be up for the same question.
     */
    fun reportVisible(viaOverlay: Boolean) {
        val shownAt = shownAtElapsedMs ?: return
        if (visible) return
        visible = true

        val latencyMs = (clock.now().elapsedMs - shownAt).coerceAtLeast(0L)
        mutableLastLatencyMs.value = latencyMs
        // The flow is the app's answer to "which host owns the surface", so a reporting host sets
        // it: the overlay keeps its window, and an activity that won the race cancels the request.
        mutableOverlayRequested.value = viaOverlay

        // §3.6: the one line that answers "how long did the pause take to appear".
        Log.i(TAG, "eventToVisible=${latencyMs}ms via=${if (viaOverlay) "overlay" else "activity"}")
    }

    /** The question is gone: no surface, no overlay, no live clock. */
    fun reportGone() {
        shownAtElapsedMs = null
        visible = false
        mutableOverlayRequested.value = false
        mutableUi.value = null
    }

    /**
     * The host is no longer on screen (another app covered it, or the user went home) but the engine
     * is still asking a question. Clearing only the visibility latch — not the ui — lets the next
     * UpdateSurface relaunch a host instead of silently updating a screen nobody can see.
     */
    fun reportHidden() {
        visible = false
    }

    /** Ask for the overlay fallback. Called by the presenter when the activity missed its window. */
    fun requestOverlay() {
        if (!visible) mutableOverlayRequested.value = true
    }

    private companion object {
        const val TAG = "SP/Latency"
    }
}
