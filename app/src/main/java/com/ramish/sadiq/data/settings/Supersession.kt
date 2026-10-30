package com.ramish.sadiq.data.settings

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.strict.SettingsChange

/*
 * Dropping a queued row that a newer edit has made pointless (AUDIT-5 A5-03, A5-10).
 *
 * A waiting edit is only worth keeping if it still has something to say. Two things used to make
 * this code answer "nothing" when that was false.
 *
 * **A5-03 — judged against the wrong list.** The landing row used to be compared as its stale
 * snapshot (`applied.apps`), so a queued removal of an app that had been added after that snapshot
 * was taken looked unnecessary and was deleted: "Guarded apps · N · Takes effect at 23:14"
 * disappeared early and the app stayed guarded. The comparison now uses the list that was actually
 * written ([isMadePointlessBy] takes it as an argument), which is the only list that describes the
 * table it is replacing.
 *
 * **A5-10 — judged by type instead of by field.** `SetRamadan` carries a mode and a Hijri offset;
 * `SetStrict` carries a switch and a delay; `SetFlags` carries three independent switches. Comparing
 * the whole type meant that correcting the offset deleted a queued "Ramadan off", and that changing
 * the delay deleted a queued "Think it over off". Each edit is now reduced to the fields it really
 * changes — measured against the settings in force when it was applied, which is the one baseline
 * both the queued row and the newer edit can be measured against — and a queued row is dropped only
 * when the two sets overlap.
 *
 * A row that survives that test is then re-cut against the newer edit ([rebasedBehind]), because the
 * half of it that did not overlap is stale: replaying a queued `RamadanConfig` whole would put back
 * the offset he had just corrected. Between the two functions, a waiting row carries its own intent
 * and nothing else.
 */

/** The fields a change really moves, named so two edits can be compared field by field. */
internal fun SettingsChange.changedFieldsIn(before: GuardConfig): Set<String> = when (this) {
    is SettingsChange.SetMode -> setOf("mode")
    is SettingsChange.SetApps -> setOf("apps")
    is SettingsChange.SetSurfaceMode -> setOf("surface:$packageName")
    is SettingsChange.SetDomains -> setOf("domains")

    is SettingsChange.SetSleepGate -> buildSet {
        if (cfg.enabled != before.sleepGate.enabled) add("sleepGate.enabled")
        if (cfg.startMinuteOfDay != before.sleepGate.startMinuteOfDay) {
            add("sleepGate.startMinuteOfDay")
        }
    }

    is SettingsChange.SetMorning -> buildSet {
        if (cfg.enabled != before.morning.enabled) add("morning.enabled")
        if (cfg.endMinuteOfDay != before.morning.endMinuteOfDay) add("morning.endMinuteOfDay")
    }

    is SettingsChange.SetPrayer -> buildSet {
        if (cfg.lat != before.prayer.lat) add("prayer.lat")
        if (cfg.lon != before.prayer.lon) add("prayer.lon")
        if (cfg.place != before.prayer.place) add("prayer.place")
        if (cfg.method != before.prayer.method) add("prayer.method")
        if (cfg.madhab != before.prayer.madhab) add("prayer.madhab")
        if (cfg.windowMin != before.prayer.windowMin) add("prayer.windowMin")
        if (cfg.jumuahWindowMin != before.prayer.jumuahWindowMin) add("prayer.jumuahWindowMin")
    }

    is SettingsChange.SetRamadan -> buildSet {
        if (cfg.mode != before.ramadan.mode) add("ramadan.mode")
        if (cfg.hijriOffsetDays != before.ramadan.hijriOffsetDays) add("ramadan.hijriOffsetDays")
    }

    is SettingsChange.SetStrict -> buildSet {
        if (cfg.enabled != before.strict.enabled) add("strict.enabled")
        if (cfg.delayMin != before.strict.delayMin) add("strict.delayMin")
    }

    is SettingsChange.SetFlags -> buildSet {
        reflection?.let { wanted -> if (wanted != before.reflectionEnabled) add("flags.reflection") }
        gymHint?.let { wanted -> if (wanted != before.gymHintEnabled) add("flags.gymHint") }
        webGuard?.let { wanted -> if (wanted != before.webGuardEnabled) add("flags.webGuard") }
    }
}

/**
 * Whether this waiting change has nothing left to say now that [applied] has landed.
 *
 * - **`SetApps`** is only pointless when the list just written already dropped everything this row
 *   meant to remove. Judged against [written], never against `applied.apps`: when the applied change
 *   is itself a queued row, its snapshot is older than the removal he made after it (A5-03). A row
 *   with no removal record (`removed == null`, written by a build before A4-12) is never dropped
 *   this way — it is merged when it lands, and deleting it would throw his edit away for nothing.
 * - **`SetSurfaceMode`** is per app, so a queued edit for one package says nothing about another.
 * - **Every other type** edits named fields; the row is dropped only when this edit and the applied
 *   one both move at least one of the same fields (A5-10). A `SetRamadan` offset correction no
 *   longer deletes a queued `SetRamadan` mode change.
 *
 * @param applied the change that has just been written.
 * @param before the settings in force before that write, which both field sets are measured against.
 * @param written the app list that was actually written, for the `SetApps` case.
 */
internal fun SettingsChange.isMadePointlessBy(
    applied: SettingsChange,
    before: GuardConfig,
    written: List<GuardedApp>
): Boolean = when (this) {
    is SettingsChange.SetApps -> {
        val drops = removed
        applied is SettingsChange.SetApps && drops != null &&
            drops.none { packageName -> written.any { app -> app.packageName == packageName } }
    }

    is SettingsChange.SetSurfaceMode ->
        applied is SettingsChange.SetSurfaceMode && applied.packageName == packageName

    is SettingsChange.SetMode -> applied is SettingsChange.SetMode
    is SettingsChange.SetDomains -> applied is SettingsChange.SetDomains
    is SettingsChange.SetSleepGate ->
        applied is SettingsChange.SetSleepGate && movesTheSameField(applied, before)

    is SettingsChange.SetMorning ->
        applied is SettingsChange.SetMorning && movesTheSameField(applied, before)

    is SettingsChange.SetPrayer ->
        applied is SettingsChange.SetPrayer && movesTheSameField(applied, before)

    is SettingsChange.SetRamadan ->
        applied is SettingsChange.SetRamadan && movesTheSameField(applied, before)

    is SettingsChange.SetStrict ->
        applied is SettingsChange.SetStrict && movesTheSameField(applied, before)

    is SettingsChange.SetFlags ->
        applied is SettingsChange.SetFlags && movesTheSameField(applied, before)
}

/** Whether [other] moves at least one field this change moves, both measured against [before]. */
private fun SettingsChange.movesTheSameField(other: SettingsChange, before: GuardConfig): Boolean {
    val mine = changedFieldsIn(before)
    if (mine.isEmpty()) return false
    return mine.any { field -> field in other.changedFieldsIn(before) }
}

/*
 * A queued value edit is a whole config, and most of it is not what he asked to change.
 *
 * `SetRamadan` carries a mode *and* a Hijri offset. A queued "Ramadan off" therefore also carries
 * the offset it saw when it was queued; replaying it ten minutes later puts the offset back, so the
 * correction he made in the meantime disappears — silently, which is the same failure A5-10 is
 * about, one step further on. The row has to be re-cut against the write that landed: the fields it
 * moved keep its own values, everything else takes the newer edit's.
 *
 * Which fields it moved is measurable, because both values are in hand at that moment: the row's
 * config and the settings it was queued against. Nothing has to be stored on the row for this, and
 * a field the row set to the value already in force is not a move.
 */

/** This queued change, re-cut so it carries only what it meant to change. */
internal fun SettingsChange.rebasedBehind(
    applied: SettingsChange,
    before: GuardConfig
): SettingsChange = when (this) {
    is SettingsChange.SetRamadan -> {
        if (applied !is SettingsChange.SetRamadan) {
            this
        } else {
            val merged = RamadanConfig(
                mode = keep(cfg.mode, before.ramadan.mode, applied.cfg.mode),
                hijriOffsetDays = keep(
                    cfg.hijriOffsetDays,
                    before.ramadan.hijriOffsetDays,
                    applied.cfg.hijriOffsetDays
                ),
            )
            if (merged == cfg) this else copy(cfg = merged)
        }
    }

    is SettingsChange.SetStrict -> {
        if (applied !is SettingsChange.SetStrict) {
            this
        } else {
            val merged = StrictConfig(
                enabled = keep(cfg.enabled, before.strict.enabled, applied.cfg.enabled),
                delayMin = keep(cfg.delayMin, before.strict.delayMin, applied.cfg.delayMin),
            )
            if (merged == cfg) this else copy(cfg = merged)
        }
    }

    is SettingsChange.SetSleepGate -> {
        if (applied !is SettingsChange.SetSleepGate) {
            this
        } else {
            val merged = SleepGateConfig(
                enabled = keep(cfg.enabled, before.sleepGate.enabled, applied.cfg.enabled),
                startMinuteOfDay = keep(
                    cfg.startMinuteOfDay,
                    before.sleepGate.startMinuteOfDay,
                    applied.cfg.startMinuteOfDay
                ),
            )
            if (merged == cfg) this else copy(cfg = merged)
        }
    }

    is SettingsChange.SetMorning -> {
        if (applied !is SettingsChange.SetMorning) {
            this
        } else {
            val merged = MorningConfig(
                enabled = keep(cfg.enabled, before.morning.enabled, applied.cfg.enabled),
                endMinuteOfDay = keep(
                    cfg.endMinuteOfDay,
                    before.morning.endMinuteOfDay,
                    applied.cfg.endMinuteOfDay
                ),
            )
            if (merged == cfg) this else copy(cfg = merged)
        }
    }

    is SettingsChange.SetFlags -> {
        if (applied !is SettingsChange.SetFlags) {
            this
        } else {
            val merged = SettingsChange.SetFlags(
                reflection = keepFlag(reflection, before.reflectionEnabled, applied.reflection),
                gymHint = keepFlag(gymHint, before.gymHintEnabled, applied.gymHint),
                webGuard = keepFlag(webGuard, before.webGuardEnabled, applied.webGuard),
            )
            if (merged == this) this else merged
        }
    }

    // A single-value change carries nothing else it could put back, and `SetPrayer` is always
    // neutral, so the gate never defers it: there is no queued row to re-cut.
    is SettingsChange.SetMode -> this
    is SettingsChange.SetApps -> this
    is SettingsChange.SetSurfaceMode -> this
    is SettingsChange.SetDomains -> this
    is SettingsChange.SetPrayer -> this
}

/** [mine] where this row moved the field, [theirs] where it did not. */
private fun <T> keep(mine: T, was: T, theirs: T): T = if (mine != was) mine else theirs

/** The same, for the nullable `SetFlags` fields, where `null` already means "leave it alone". */
private fun keepFlag(mine: Boolean?, was: Boolean, theirs: Boolean?): Boolean? =
    if (mine != null && mine != was) mine else theirs
