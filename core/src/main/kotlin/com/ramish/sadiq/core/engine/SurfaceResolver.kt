package com.ramish.sadiq.core.engine

import com.ramish.sadiq.core.insight.ReflectionPolicy
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.WindowKind

/**
 * Picks the surface for a guarded open and answers which of its softer options are allowed.
 *
 * The order is fixed by §3.6 (first match wins):
 * PRAYER → FOCUS_GUARD → SLEEP_GATE → COOLDOWN → MORNING → REFLECT → PAUSE.
 *
 * The resolver is deliberately ignorant of time arithmetic: it only compares the window and the
 * three flags it is handed, so its order can be tested without a clock.
 */
class SurfaceResolver(private val reflection: ReflectionPolicy) {

    /**
     * @param focusRunning true while a focus session is running.
     * @param restActive true while a cooldown rest still has time left.
     * @param recentOpensWall wall times of guarded opens, oldest first.
     */
    fun resolve(
        ctx: GuardContext,
        nowWall: Long,
        focusRunning: Boolean,
        restActive: Boolean,
        recentOpensWall: List<Long> = emptyList(),
        lastReflectWall: Long? = null,
    ): SurfaceKind = when {
        ctx.window.kind == WindowKind.PRAYER -> SurfaceKind.PRAYER
        focusRunning -> SurfaceKind.FOCUS_GUARD
        ctx.window.kind == WindowKind.SLEEP_GATE -> SurfaceKind.SLEEP_GATE
        restActive -> SurfaceKind.COOLDOWN
        ctx.window.kind == WindowKind.MORNING -> SurfaceKind.MORNING
        reflection.shouldReflect(
            opensWall = recentOpensWall,
            lastReflectWall = lastReflectWall,
            nowWall = nowWall,
            enabled = ctx.config.reflectionEnabled,
        ) -> SurfaceKind.REFLECT
        else -> SurfaceKind.PAUSE
    }

    /**
     * Free scroll is offered from the soft surfaces and from a Stay time-up, and never in a
     * protected window (§3.6 T11, §3.8).
     */
    fun allowsFreeScroll(kind: SurfaceKind, ctx: GuardContext, focusRunning: Boolean): Boolean {
        if (kind !in FREE_SCROLL_KINDS) return false
        return !protectedWindowActive(ctx, focusRunning)
    }

    /** Kinds the resolver may return when it runs again from a focus prompt (T16). */
    fun withoutFocus(
        ctx: GuardContext,
        nowWall: Long,
        restActive: Boolean,
        recentOpensWall: List<Long> = emptyList(),
        lastReflectWall: Long? = null,
    ): SurfaceKind = resolve(
        ctx = ctx,
        nowWall = nowWall,
        focusRunning = false,
        restActive = restActive,
        recentOpensWall = recentOpensWall,
        lastReflectWall = lastReflectWall,
    )

    companion object {
        /** Windows in which the soft options are withheld. */
        val PROTECTED_WINDOWS: Set<WindowKind> =
            setOf(WindowKind.PRAYER, WindowKind.SLEEP_GATE, WindowKind.MORNING)

        /** Surfaces that may offer free scroll at all, before the window filter. */
        val FREE_SCROLL_KINDS: Set<SurfaceKind> =
            setOf(SurfaceKind.PAUSE, SurfaceKind.REFLECT, SurfaceKind.COOLDOWN, SurfaceKind.TIME_UP)

        /** A prayer, sleep-gate, morning or focus window withholds the soft options (§3.6, §3.8). */
        fun protectedWindowActive(ctx: GuardContext, focusRunning: Boolean): Boolean =
            focusRunning || ctx.window.kind in PROTECTED_WINDOWS

        /**
         * A quick need is the one-minute hold, offered where the alternatives are hard: a cooldown, the
         * sleep gate, focus guard, or a time-up that landed in a rest (§3.6 T12).
         *
         * One implementation, read by both the reducer's guard and the surface builder, so a chip the
         * user sees is always a choice the engine will accept.
         */
        fun allowsQuickNeed(kind: SurfaceKind, restActive: Boolean, quickNeedsLeft: Int): Boolean {
            if (quickNeedsLeft <= 0) return false
            return when (kind) {
                SurfaceKind.COOLDOWN, SurfaceKind.SLEEP_GATE, SurfaceKind.FOCUS_GUARD -> true
                SurfaceKind.TIME_UP -> restActive
                else -> false
            }
        }
    }
}
