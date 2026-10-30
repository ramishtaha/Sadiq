package com.ramish.sadiq.core.strict

import com.ramish.sadiq.core.model.GuardedApp

/*
 * Rebasing a queued app-list snapshot onto the list in force when it lands (AUDIT-4 A4-12).
 *
 * A [SettingsChange.SetApps] is a full list, and a queued one used to be written back verbatim:
 * whatever was guarded when it landed was replaced by whatever was guarded when it was made. An app
 * added between those two moments — a tightening edit, which never waits — was silently un-guarded
 * ten minutes later by a snapshot that had never heard of it. The user never saw that happen; the
 * Guard tab simply stopped listing the app he had just added.
 *
 * **The rule, exactly:** the list written is `(current ∪ snapshot) − removed`.
 *
 * - `current` is what is guarded at the moment the change lands, and each of its entries is kept
 *   with its live label and surface mode. That is the app added while the snapshot waited, and the
 *   surface mode a newer `SetSurfaceMode` may have changed since: the live row always wins.
 * - `snapshot − current` is what the edit meant to add. It is added, ten minutes late, rather than
 *   dropped, so a save that removed one app and added another does both.
 * - `removed` is what the edit meant to remove, recorded when it was queued
 *   ([withRemovalsRecorded]). Only that is subtracted, because only that is a removal the user
 *   actually asked for.
 *
 * `removed = null` means the row was written by a build that did not record removals (or by a
 * caller that built the change directly): nothing is subtracted, so the snapshot is merged in
 * instead. That can re-add an app the user dropped, which he can see in the Guard tab and undo. The
 * alternative — subtracting whatever is missing from the snapshot — would remove an app he never
 * asked to remove, which he cannot see at all. The two failures are not the same size.
 *
 * `removed` and `snapshot` are disjoint by construction ([withRemovalsRecorded] takes `current` minus
 * `snapshot`), which is why the rule collapses to `(current ∪ snapshot) − removed` and never has to
 * decide what to do with a package that is both meant to go and meant to arrive.
 */

/**
 * The guard list to write when this queued snapshot lands on top of [current].
 *
 * @param current the apps guarded now, in the order the table holds them.
 * @return the merged list: what is guarded now, minus only what this edit meant to remove, plus
 *   anything it meant to add.
 */
fun SettingsChange.SetApps.rebasedOnto(current: List<GuardedApp>): List<GuardedApp> {
    val subtract = removed.orEmpty()
    val guardedNow = current.mapTo(mutableSetOf()) { app -> app.packageName }
    val kept = current.filterNot { app -> app.packageName in subtract }
    val additions = apps.filter { app ->
        app.packageName !in guardedNow && app.packageName !in subtract
    }
    return kept + additions
}

/**
 * The row to queue for a snapshot the gate deferred: the same list, plus the packages it drops.
 *
 * The snapshot cannot carry this on its own later, because the list it is compared against is not
 * the list that will be in force when it lands. Recording it here, while the current list is still
 * the one the user was looking at, is what makes [rebasedOnto] able to tell a removal he asked for
 * from an app he added afterwards.
 *
 * Recorded removals are only ever added to, never replaced, so a row that is deferred twice keeps
 * everything the first deferral claimed.
 *
 * @param guardedNow the apps guarded at the moment the edit was made.
 */
fun SettingsChange.SetApps.withRemovalsRecorded(
    guardedNow: List<GuardedApp>
): SettingsChange.SetApps {
    val guarded = guardedNow.mapTo(mutableSetOf()) { app -> app.packageName }
    val keptBySnapshot = apps.mapTo(mutableSetOf()) { app -> app.packageName }
    return copy(removed = guarded.minus(keptBySnapshot) + removed.orEmpty())
}

/**
 * Whether this queued change has nothing left to say now that [applied] has landed.
 *
 * The applier already keeps a due snapshot from overwriting a newer list. The other half of the same
 * bug is a queued edit of any other kind: a `SetMode(GENTLE)` waiting ten minutes silently undoes
 * the Firm the user chose while it waited. So when an edit of the same kind is applied **now**, the
 * queued row it replaces is dropped instead of being replayed later.
 *
 * "Same kind" is the change type, plus the subject where a type has one:
 * - `SetApps` is only superseded when the list just written already drops everything the queued row
 *   meant to remove. If the new list still guards one of those packages — the picker re-renders an
 *   app whose removal has not landed yet — the queued row still carries a removal the user asked
 *   for and is kept, to be rebased and applied at its own moment.
 * - `SetSurfaceMode` is per app, so a queued edit for one package says nothing about another.
 * - Every other type edits one value, so the newer write replaces the older one outright.
 *
 * @param applied the change that has just been written.
 */
fun SettingsChange.isSupersededBy(applied: SettingsChange): Boolean = when (this) {
    is SettingsChange.SetApps -> applied is SettingsChange.SetApps &&
        removed.orEmpty().none { packageName ->
            applied.apps.any { app -> app.packageName == packageName }
        }

    is SettingsChange.SetSurfaceMode -> applied is SettingsChange.SetSurfaceMode &&
        applied.packageName == packageName

    is SettingsChange.SetMode -> applied is SettingsChange.SetMode
    is SettingsChange.SetDomains -> applied is SettingsChange.SetDomains
    is SettingsChange.SetSleepGate -> applied is SettingsChange.SetSleepGate
    is SettingsChange.SetMorning -> applied is SettingsChange.SetMorning
    is SettingsChange.SetPrayer -> applied is SettingsChange.SetPrayer
    is SettingsChange.SetRamadan -> applied is SettingsChange.SetRamadan
    is SettingsChange.SetStrict -> applied is SettingsChange.SetStrict
    is SettingsChange.SetFlags -> applied is SettingsChange.SetFlags
}
