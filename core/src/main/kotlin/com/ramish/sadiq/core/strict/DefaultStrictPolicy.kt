package com.ramish.sadiq.core.strict

import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.model.SurfaceMode

/**
 * "Think it over": the classification table of WP-27, and the length of the wait.
 *
 * The rule the user sees is one sentence — *loosening a rule waits ten minutes* — so the only thing
 * that matters here is which edits count as loosening. The table below is the source of truth, and
 * every branch is asserted in `DefaultStrictPolicyTest`:
 *
 * | Edit | Loosening | Tightening |
 * |---|---|---|
 * | Mode | a gentler mode (Gentle < Steady < Firm) | a firmer mode |
 * | Guarded apps | removing an app, or narrowing it to shorts | adding an app, or guarding the whole app |
 * | Domains | removing a domain | adding a domain |
 * | Bedtime | switching it off, or a later start | switching it on, or an earlier start |
 * | Morning | switching it off, or an earlier end | switching it on, or a later end |
 * | Ramadan | leaving [RamadanMode.ON] | turning it on |
 * | This feature | switching it off, or a shorter wait | switching it on, or a longer wait |
 * | Switches | check-ins off, or web guard off | check-ins on, or web guard on |
 * | Prayer inputs, theme, sound, the gym hint | — | — |
 *
 * Three decisions are worth stating because the table could have gone another way:
 *
 * 1. **A master switch decides alone.** Turning the bedtime gate on while moving its start later is
 *    a net tightening: the window now exists. The same holds for the morning window and for this
 *    feature's own switch. Comparing the times alone would delay the one change that protects the
 *    most.
 * 2. **Removal wins inside one list.** A swap — remove Instagram, add TikTok — is classified
 *    loosening, because a guarded app is no longer guarded and the wait is the safe side. Adding an
 *    app to a list that also drops one therefore waits.
 * 3. **Inert edits are neutral.** Moving a bedtime that is switched off, editing domains while web
 *    guard is off, or correcting the Hijri offset changes nothing in force, so it does not wait.
 *
 * The class is stateless and clock-free apart from the [nowWall] argument, so replaying the same
 * settings always produces the same answer.
 */
class DefaultStrictPolicy : StrictPolicy {

    /**
     * Classifies one edit against the settings currently in force.
     *
     * @param current the settings in force.
     * @param change the edit being requested.
     * @return whether the edit makes the app stricter, looser, or neither.
     */
    override fun classify(current: GuardConfig, change: SettingsChange): ChangeClass = when (change) {
        is SettingsChange.SetMode -> modeClass(current.mode, change.mode)
        is SettingsChange.SetApps -> appsClass(current, change.apps)
        is SettingsChange.SetSurfaceMode -> surfaceModeClass(current, change)
        is SettingsChange.SetDomains -> domainsClass(current, change.domains)
        is SettingsChange.SetSleepGate -> sleepGateClass(current.sleepGate, change.cfg)
        is SettingsChange.SetMorning -> morningClass(current.morning, change.cfg)
        is SettingsChange.SetPrayer -> ChangeClass.NEUTRAL
        is SettingsChange.SetRamadan -> ramadanClass(current.ramadan, change.cfg)
        is SettingsChange.SetStrict -> strictClass(current.strict, change.cfg)
        is SettingsChange.SetFlags -> flagsClass(current, change)
    }

    /**
     * When a classified edit may take effect.
     *
     * Tightening and neutral edits land at [nowWall]. A loosening edit waits [StrictConfig.delayMin]
     * minutes, or [RAMADAN_DELAY_MIN] during Ramadan: the month is when a 2 a.m. decision is most
     * expensive and when the user asked for the longest protection.
     *
     * A non-positive delay means the user turned the wait off by hand; the edit then lands at once
     * rather than in the past. Ramadan still applies its own thirty minutes — the mode is the
     * stricter promise, and turning it on is what asked for it.
     *
     * @param nowWall wall time of the request.
     * @param cls how the change was classified.
     * @param strict the delay settings.
     * @param ramadanActive whether Ramadan is in force.
     * @return the wall time the edit takes effect; equal to [nowWall] unless it is a loosening.
     */
    override fun applyAtWall(
        nowWall: Long,
        cls: ChangeClass,
        strict: StrictConfig,
        ramadanActive: Boolean
    ): Long {
        if (cls != ChangeClass.LOOSENING) return nowWall
        val delayMin = if (ramadanActive) RAMADAN_DELAY_MIN else strict.delayMin.coerceAtLeast(0)
        return nowWall + delayMin.toLong() * MINUTE_MS
    }

    // ── one branch per SettingsChange ───────────────────────────────────────────────────────────

    /** A gentler mode spends more guarded time and rests less: Gentle → Steady → Firm. */
    private fun modeClass(before: Mode, after: Mode): ChangeClass = when {
        strictness(after) > strictness(before) -> ChangeClass.TIGHTENING
        strictness(after) < strictness(before) -> ChangeClass.LOOSENING
        else -> ChangeClass.NEUTRAL
    }

    /**
     * The guarded app list, compared by package and by surface.
     *
     * A label edit is invisible here: the label is decoration, the package is the guard. Removing a
     * package is loosening even when another is added in the same edit (decision 2 in the class
     * documentation), and narrowing a guarded app to shorts is the same loosening the surface-mode
     * edit is.
     */
    private fun appsClass(current: GuardConfig, next: List<GuardedApp>): ChangeClass {
        val before = current.apps.associate { app -> app.packageName to app.surfaceMode }
        val after = next.associate { app -> app.packageName to app.surfaceMode }

        if ((before.keys - after.keys).isNotEmpty()) return ChangeClass.LOOSENING
        if ((after.keys - before.keys).isNotEmpty()) return ChangeClass.TIGHTENING

        var tightened = false
        for ((packageName, mode) in after) {
            val previous = before[packageName] ?: continue
            if (previous == mode) continue
            if (mode == SurfaceMode.WHOLE_APP) tightened = true else return ChangeClass.LOOSENING
        }
        return if (tightened) ChangeClass.TIGHTENING else ChangeClass.NEUTRAL
    }

    /**
     * One app's surface. The whole app is stricter than its short-video surface, and an app that is
     * not guarded at all cannot become stricter or looser by this edit.
     */
    private fun surfaceModeClass(current: GuardConfig, change: SettingsChange.SetSurfaceMode): ChangeClass {
        val guarded = current.apps.firstOrNull { app -> app.packageName == change.packageName }
            ?: return ChangeClass.NEUTRAL
        if (guarded.surfaceMode == change.mode) return ChangeClass.NEUTRAL
        return if (change.mode == SurfaceMode.WHOLE_APP) ChangeClass.TIGHTENING else ChangeClass.LOOSENING
    }

    /**
     * Guarded domains. With web guard off the list is not enforced, so editing it changes nothing
     * in force and does not wait — turning web guard on is the edit that tightens.
     */
    private fun domainsClass(current: GuardConfig, next: Set<String>): ChangeClass {
        if (!current.webGuardEnabled) return ChangeClass.NEUTRAL
        if ((current.domains - next).isNotEmpty()) return ChangeClass.LOOSENING
        return if ((next - current.domains).isNotEmpty()) ChangeClass.TIGHTENING else ChangeClass.NEUTRAL
    }

    /**
     * The bedtime gate. The switch decides alone (decision 1): on → off is loosening, off → on is
     * tightening. With the gate on and unchanged, a later start shortens the window, so it loosens;
     * an earlier start lengthens it, so it tightens.
     */
    private fun sleepGateClass(before: SleepGateConfig, after: SleepGateConfig): ChangeClass {
        if (before.enabled != after.enabled) {
            return if (after.enabled) ChangeClass.TIGHTENING else ChangeClass.LOOSENING
        }
        if (!after.enabled) return ChangeClass.NEUTRAL
        return when {
            after.startMinuteOfDay > before.startMinuteOfDay -> ChangeClass.LOOSENING
            after.startMinuteOfDay < before.startMinuteOfDay -> ChangeClass.TIGHTENING
            else -> ChangeClass.NEUTRAL
        }
    }

    /**
     * The morning window. Mirror of the bedtime gate: the switch decides alone, then an earlier end
     * shortens the protected morning and loosens.
     */
    private fun morningClass(before: MorningConfig, after: MorningConfig): ChangeClass {
        if (before.enabled != after.enabled) {
            return if (after.enabled) ChangeClass.TIGHTENING else ChangeClass.LOOSENING
        }
        if (!after.enabled) return ChangeClass.NEUTRAL
        return when {
            after.endMinuteOfDay < before.endMinuteOfDay -> ChangeClass.LOOSENING
            after.endMinuteOfDay > before.endMinuteOfDay -> ChangeClass.TIGHTENING
            else -> ChangeClass.NEUTRAL
        }
    }

    /**
     * Ramadan mode. Only [RamadanMode.ON] changes the schedule, so entering it tightens and leaving
     * it loosens. Moving between `OFF` and `AUTO_SUGGEST` only changes whether the card is offered,
     * which is not a guard decision and does not wait. The Hijri offset is a calendar correction, so
     * it is neutral.
     */
    private fun ramadanClass(before: RamadanConfig, after: RamadanConfig): ChangeClass = when {
        before.mode == after.mode -> ChangeClass.NEUTRAL
        after.mode == RamadanMode.ON -> ChangeClass.TIGHTENING
        before.mode == RamadanMode.ON -> ChangeClass.LOOSENING
        else -> ChangeClass.NEUTRAL
    }

    /**
     * This feature's own switch. Turning it off must wait: that deferral is the whole promise, and
     * it is the one change that would otherwise disable every other delay at once.
     *
     * With the switch on and unchanged, a longer wait tightens and a shorter wait loosens — the wait
     * is the only thing the setting controls. With the switch off the delay is inert, so editing it
     * applies at once; that is how the user can set "30 minutes" before switching the feature on.
     */
    private fun strictClass(before: StrictConfig, after: StrictConfig): ChangeClass {
        if (before.enabled != after.enabled) {
            return if (after.enabled) ChangeClass.TIGHTENING else ChangeClass.LOOSENING
        }
        if (!after.enabled) return ChangeClass.NEUTRAL
        return when {
            after.delayMin > before.delayMin -> ChangeClass.TIGHTENING
            after.delayMin < before.delayMin -> ChangeClass.LOOSENING
            else -> ChangeClass.NEUTRAL
        }
    }

    /**
     * Check-ins and web guard. Both are independent switches, so a loosening half is never allowed
     * to ride in on a tightening half: an edit that turns check-ins back on while switching web
     * guard off waits, and the user sees it listed in Guard before it lands. The gym hint is
     * encouragement, not a guard, and a null field means "leave this one alone".
     */
    private fun flagsClass(current: GuardConfig, change: SettingsChange.SetFlags): ChangeClass {
        var tightened = false
        var loosened = false

        change.reflection?.let { reflection ->
            if (reflection != current.reflectionEnabled) {
                if (reflection) tightened = true else loosened = true
            }
        }
        change.webGuard?.let { webGuard ->
            if (webGuard != current.webGuardEnabled) {
                if (webGuard) tightened = true else loosened = true
            }
        }
        return when {
            loosened -> ChangeClass.LOOSENING
            tightened -> ChangeClass.TIGHTENING
            else -> ChangeClass.NEUTRAL
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /** The one ordering the table needs: Gentle is the loosest, Firm the tightest. */
    private fun strictness(mode: Mode): Int = when (mode) {
        Mode.GENTLE -> 0
        Mode.BALANCED -> 1
        Mode.HARDCORE -> 2
    }

    companion object {
        /** §3.7: during Ramadan a loosening edit waits this long instead of `strict.delayMin`. */
        const val RAMADAN_DELAY_MIN: Int = 30

        /** Milliseconds in a minute, named so [applyAtWall] reads as a duration. */
        private const val MINUTE_MS: Long = 60_000L
    }
}
