package com.ramish.sadiq.data.settings

import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange

/*
 * Composing queued app-list edits (AUDIT-5 A5-01, A5-02, A5-03).
 *
 * A queued `SetApps` is a **snapshot**: the complete list the user wanted, taken from a table that
 * has moved on by the time the row lands. AUDIT-4 A4-12 taught the write path to subtract only what
 * the row recorded as removed (`SettingsChange.SetApps.removed`), which is right for one row. Two
 * rows break it, because the other half of that rule — `snapshot − current` is "what this edit meant
 * to add" — is not a fact about the edit at all. It is whatever the table happens to be missing when
 * the row lands, and that includes everything an earlier queued row has already removed.
 *
 * The audit's own example (A5-02): row A removes Instagram; row B is queued from a list that still
 * shows Instagram and removes only TikTok. A lands, then B lands, and Instagram comes back — B's
 * snapshot counted it as an addition because the table no longer had it.
 *
 * **The rule this file keeps: a queued snapshot is never allowed to go stale.** Every write that
 * changes the guarded set also composes the rows still waiting:
 *
 * - a package the write stopped guarding is struck out of their snapshots, so no waiting row can
 *   re-add what an earlier one removed (`rewritesAfterAppWrite`);
 * - a package the write started guarding cancels any queued removal of it, because guarding an app
 *   is the one thing a removal record cannot survive: he just asked for the opposite
 *   (`rewritesCancellingRemovals`).
 *
 * A row is then rebased at its own moment by the unchanged `rebasedOnto` rule, and — because its
 * snapshot no longer names anything an earlier row removed — `snapshot − current` only ever adds
 * what the edit really did add. That is A5-02 without adding a field to `SetApps`: the queue, not
 * the individual row, is what knows the order of his edits.
 *
 * Two smaller pieces live here too, both about the moment a row is queued:
 * - [deferredAgainst] records the removal against the table in force rather than against the cached
 *   config, and leaves out what an earlier row is already removing (A5-01's double record);
 * - [queuedRemovals] is how a new row finds out what the rows before it are already going to drop.
 *
 * A rewrite is `remove` + `add` with the row's own `applyAt`, because the queue contract has no
 * update. The id changes; nothing a user can see does. A row that is already due is left out of the
 * snapshot-striking rule: it is about to be applied by `PendingChangeApplier`, which composes the
 * rows it applies in one pass by passing the removals it has made so far into the next one.
 */

/**
 * The packages every queued `SetApps` row says it is going to drop.
 *
 * Used when a new row is recorded, so a second edit cannot claim a removal the first one already
 * owns (A5-01: the picker used to show the app as ticked and the save recorded the same removal
 * again).
 */
internal fun Iterable<PendingChange>.queuedRemovals(): Set<String> =
    flatMapTo(mutableSetOf()) { row -> (row.change as? SettingsChange.SetApps)?.removed.orEmpty() }

/** The packages [written] guards that [before] did not. */
private fun addedBetween(before: List<GuardedApp>, written: List<GuardedApp>): Set<String> {
    val had = before.mapTo(mutableSetOf()) { app -> app.packageName }
    return written.mapNotNullTo(mutableSetOf()) { app -> app.packageName.takeIf { it !in had } }
}

/** The packages [before] guarded that [written] does not. */
private fun droppedBetween(before: List<GuardedApp>, written: List<GuardedApp>): Set<String> {
    val has = written.mapTo(mutableSetOf()) { app -> app.packageName }
    return before.mapNotNullTo(mutableSetOf()) { app -> app.packageName.takeIf { it !in has } }
}

/**
 * The row to queue for a snapshot the gate deferred.
 *
 * Three things are recorded while the table the user was looking at is still in hand:
 *
 * - **the removal**, as `guarded − snapshot`, but taken from [guardedNow] — the table — rather than
 *   from the cached config, which a write may already have overtaken;
 * - **only the removal this edit is responsible for**: a package an earlier queued row is already
 *   going to drop is left out, so two waiting rows never each claim it (A5-01);
 * - **the snapshot minus those pending removals**, so this row does not re-add a package another
 *   row is about to take away (A5-02). Its additions are untouched: a package that is in the table
 *   is not an addition, and one that is not is.
 *
 * @param guardedNow the apps guarded in the table when the edit was made.
 * @param alreadyQueuedRemovals what every waiting row already says it will drop.
 */
internal fun SettingsChange.SetApps.deferredAgainst(
    guardedNow: List<GuardedApp>,
    alreadyQueuedRemovals: Set<String>
): SettingsChange.SetApps {
    val snapshot = apps.filterNot { app -> app.packageName in alreadyQueuedRemovals }
    val guarded = guardedNow.mapTo(mutableSetOf()) { app -> app.packageName }
    val kept = snapshot.mapTo(mutableSetOf()) { app -> app.packageName }
    val drops = (guarded - kept) - alreadyQueuedRemovals
    return copy(apps = snapshot, removed = drops + removed.orEmpty())
}

/** The same row with [packages] struck out of its snapshot — they are gone, not to be re-added. */
internal fun SettingsChange.SetApps.withoutGuarding(packages: Set<String>): SettingsChange.SetApps {
    if (packages.isEmpty()) return this
    val kept = apps.filterNot { app -> app.packageName in packages }
    return if (kept.size == apps.size) this else copy(apps = kept)
}

/**
 * The same row with [packages] taken out of its removal record — he re-guarded them after queueing
 * it, so this row must not drop them when it lands.
 *
 * An empty record is left as an empty set rather than `null`: `null` means "written by a build that
 * did not record removals" and makes the row merge instead of replace, which is a different claim.
 */
internal fun SettingsChange.SetApps.withoutRemovalOf(packages: Set<String>): SettingsChange.SetApps {
    val recorded = removed ?: return this
    val kept = recorded - packages
    return if (kept.size == recorded.size) this else copy(removed = kept)
}

/** One waiting row, and the record that should replace it. */
internal data class QueuedAppRewrite(
    val row: PendingChange,
    val replacement: SettingsChange.SetApps
)

/**
 * The rows that must lose a removal because [packages] are guarded again.
 *
 * Called from two places, for the same reason. The picker calls it when he ticks an app whose
 * removal is still waiting: the table already holds that app, so the save itself is a no-op and only
 * this can call the removal off. The data layer calls it when a **live** list starts guarding a
 * package that a waiting row removes — which is the same intent one moment later, and covers every
 * other screen that can re-add an app.
 *
 * @param packages the packages that must stay guarded.
 */
internal fun Iterable<PendingChange>.rewritesCancellingRemovals(
    packages: Set<String>
): List<QueuedAppRewrite> {
    if (packages.isEmpty()) return emptyList()
    return mapNotNull { row ->
        val queued = row.change as? SettingsChange.SetApps ?: return@mapNotNull null
        val replacement = queued.withoutRemovalOf(packages)
        if (replacement == queued) null else QueuedAppRewrite(row, replacement)
    }
}

/**
 * The waiting rows to rewrite after one write landed.
 *
 * Two rules, both about the gap between a snapshot and the list it will be rebased onto:
 *
 * 1. **Nothing a write removed may be re-added by a row queued before it.** The packages that just
 *    stopped being guarded are struck out of every waiting snapshot. Without this, A5-02's second
 *    row counts the first row's removal as its own addition.
 * 2. **A live write that guards a package cancels a queued removal of it.** Only for a live write:
 *    a landing row's "additions" are the stale half of an old snapshot, not a decision he made now,
 *    and cancelling a later row's removal on their strength would un-guard something he asked for.
 *
 * Rows that are already due are skipped by rule 1 — the applier is about to apply them, and it
 * composes the due batch in order itself. Rule 2 applies to every waiting row, because the write
 * that guards the package is newer than all of them.
 *
 * @param before the apps guarded before the write.
 * @param written the apps guarded after it.
 * @param nowWall the write's wall time, which decides which rows are already due.
 * @param live whether the write is a fresh edit (`removed == null`) rather than a landing row.
 */
internal fun Iterable<PendingChange>.rewritesAfterAppWrite(
    before: List<GuardedApp>,
    written: List<GuardedApp>,
    nowWall: Long,
    live: Boolean
): List<QueuedAppRewrite> {
    val added = if (live) addedBetween(before, written) else emptySet()
    val dropped = droppedBetween(before, written)
    if (added.isEmpty() && dropped.isEmpty()) return emptyList()

    return mapNotNull { row ->
        val queued = row.change as? SettingsChange.SetApps ?: return@mapNotNull null
        var replacement = queued
        if (added.isNotEmpty()) replacement = replacement.withoutRemovalOf(added)
        if (dropped.isNotEmpty() && row.applyAt > nowWall) {
            replacement = replacement.withoutGuarding(dropped)
        }
        if (replacement == queued) null else QueuedAppRewrite(row, replacement)
    }
}
