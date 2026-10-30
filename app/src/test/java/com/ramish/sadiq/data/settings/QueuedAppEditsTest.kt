package com.ramish.sadiq.data.settings

import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.strict.rebasedOnto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val INSTAGRAM: String = "com.instagram.android"
private const val TIKTOK: String = "com.zhiliaoapp.musically"
private const val YOUTUBE: String = "com.google.android.youtube"
private const val REDDIT: String = "com.reddit.frontpage"

private val instagram = GuardedApp(INSTAGRAM, "Instagram")
private val tiktok = GuardedApp(TIKTOK, "TikTok")
private val youtube = GuardedApp(YOUTUBE, "YouTube")
private val reddit = GuardedApp(REDDIT, "Reddit")

private fun pending(id: Long, change: SettingsChange, applyAt: Long) =
    PendingChange(id = id, createdAt = 0L, applyAt = applyAt, change = change)

private fun packages(apps: List<GuardedApp>): Set<String> =
    apps.mapTo(mutableSetOf()) { app -> app.packageName }

/**
 * Composing two or more queued app edits (AUDIT-5 A5-01, A5-02).
 *
 * AUDIT-4 A4-12 fixed one row; every case here has at least two, because that is where a snapshot
 * stops meaning what the user saw when he made it. The arithmetic is asserted without a database, a
 * gate or a clock — `SettingsRepositoryImpl` and `PendingChangeApplier` both run these functions, so
 * a mistake here is a mistake in the app.
 */
class QueuedAppEditsTest {

    // ── a row is queued ─────────────────────────────────────────────────────────────────────────

    /**
     * A5-01's first half: the second save must not record a removal the first one already owns.
     *
     * He un-ticked Instagram (row A). The picker used to keep showing it ticked, so he un-ticked it
     * again alongside TikTok. Recording both would give two rows that each claim Instagram, and the
     * second would subtract it again after he had re-added it.
     */
    @Test
    fun aSecondRowDoesNotClaimARemovalThatIsAlreadyQueued() {
        val second = SettingsChange.SetApps(apps = listOf(instagram, youtube)).deferredAgainst(
            guardedNow = listOf(instagram, tiktok, youtube),
            alreadyQueuedRemovals = setOf(INSTAGRAM),
        )

        assertEquals(
            setOf(TIKTOK),
            second.removed,
            "Instagram is already on its way out; this row is only responsible for TikTok",
        )
    }

    /**
     * A5-02's root: the snapshot must not keep a package another row is already dropping, or landing
     * here will count it as an addition and guard it again.
     */
    @Test
    fun aSecondRowSnapshotLosesAnAlreadyQueuedRemoval() {
        val second = SettingsChange.SetApps(apps = listOf(instagram, youtube)).deferredAgainst(
            guardedNow = listOf(instagram, tiktok, youtube),
            alreadyQueuedRemovals = setOf(INSTAGRAM),
        )

        assertEquals(
            setOf(YOUTUBE),
            packages(second.apps),
            "Instagram is not this row's to keep; the earlier row is removing it",
        )
    }

    /** The removal is measured against the table, and additions in the snapshot survive. */
    @Test
    fun aNewRowKeepsTheAppsItAdds() {
        val queued = SettingsChange.SetApps(apps = listOf(youtube, reddit)).deferredAgainst(
            guardedNow = listOf(instagram, youtube),
            alreadyQueuedRemovals = emptySet(),
        )

        assertEquals(setOf(INSTAGRAM), queued.removed)
        assertEquals(setOf(YOUTUBE, REDDIT), packages(queued.apps))
    }

    /** Nothing is invented when the queue is empty: a swap still records both halves of itself. */
    @Test
    fun theFirstRowOfAQueueRecordsItsOwnRemoval() {
        val queued = SettingsChange.SetApps(apps = listOf(youtube)).deferredAgainst(
            guardedNow = listOf(instagram, youtube, tiktok),
            alreadyQueuedRemovals = emptySet(),
        )

        assertEquals(setOf(INSTAGRAM, TIKTOK), queued.removed)
    }

    @Test
    fun queuedRemovalsIsEveryWaitingRowsRecord() {
        val rows = listOf(
            pending(1, SettingsChange.SetApps(listOf(youtube), removed = setOf(INSTAGRAM)), 10),
            pending(2, SettingsChange.SetApps(listOf(youtube), removed = setOf(TIKTOK)), 20),
            pending(3, SettingsChange.SetMode(Mode.GENTLE), 30),
        )

        assertEquals(setOf(INSTAGRAM, TIKTOK), rows.queuedRemovals())
    }

    // ── a row lands ────────────────────────────────────────────────────────────────────────────

    /**
     * A5-02, at the point where it happens: row A removed Instagram and has just landed. Row B was
     * queued from a table that still showed Instagram, so its snapshot names it. Landing B unchanged
     * would guard Instagram again, ten minutes after he watched it go.
     */
    @Test
    fun aWaitingSnapshotLosesWhatALandedRowRemoved() {
        val rowB = pending(
            id = 2,
            change = SettingsChange.SetApps(apps = listOf(instagram, youtube), removed = setOf(TIKTOK)),
            applyAt = 2_000L,
        )

        val rewrites = listOf(rowB).rewritesAfterAppWrite(
            before = listOf(instagram, tiktok, youtube),
            written = listOf(tiktok, youtube),
            nowWall = 1_000L,
            live = false,
        )

        val replacement = rewrites.single().replacement
        assertEquals(
            setOf(YOUTUBE),
            packages(replacement.apps),
            "Instagram was removed by the row that just landed; B must not put it back",
        )
        assertEquals(setOf(TIKTOK), replacement.removed, "and B still drops TikTok")
    }

    /** A row that is already due is composed by the applier instead, which knows the batch order. */
    @Test
    fun aDueRowIsLeftForTheApplier() {
        val rowB = pending(
            id = 2,
            change = SettingsChange.SetApps(apps = listOf(instagram, youtube), removed = setOf(TIKTOK)),
            applyAt = 500L,
        )

        val rewrites = listOf(rowB).rewritesAfterAppWrite(
            before = listOf(instagram, tiktok, youtube),
            written = listOf(tiktok, youtube),
            nowWall = 1_000L,
            live = false,
        )

        assertTrue(rewrites.isEmpty(), "a due row lands through the applier in the same pass")
    }

    /**
     * A5-01's second half: he re-added an app whose removal was queued. The list he saved now guards
     * it, so the queued row must stop planning to drop it — otherwise it un-guards an app he guarded
     * a minute ago, with no prompt.
     */
    @Test
    fun aLiveWriteThatGuardsAnAppCancelsAQueuedRemovalOfIt() {
        val row = pending(
            id = 1,
            change = SettingsChange.SetApps(apps = listOf(youtube), removed = setOf(INSTAGRAM, TIKTOK)),
            applyAt = 2_000L,
        )

        val rewrites = listOf(row).rewritesAfterAppWrite(
            before = listOf(tiktok, youtube),
            written = listOf(instagram, tiktok, youtube),
            nowWall = 1_000L,
            live = true,
        )

        val replacement = rewrites.single().replacement
        assertEquals(
            setOf(TIKTOK),
            replacement.removed,
            "only Instagram was re-guarded, so only its removal is called off",
        )
    }

    /**
     * A landing row's "additions" are the stale half of an old snapshot, not a decision he made now,
     * so they never cancel a later row's removal. Otherwise a row that adds an app would silently
     * overrule the row in which he asked for it to go.
     */
    @Test
    fun aLandingRowDoesNotCancelALaterRemoval() {
        val row = pending(
            id = 2,
            change = SettingsChange.SetApps(apps = listOf(youtube), removed = setOf(INSTAGRAM)),
            applyAt = 2_000L,
        )

        val rewrites = listOf(row).rewritesAfterAppWrite(
            before = listOf(youtube),
            written = listOf(instagram, youtube),
            nowWall = 1_000L,
            live = false,
        )

        assertTrue(rewrites.isEmpty(), "a queued snapshot cannot cancel a removal he made after it")
    }

    /** A row that adds nothing and drops nothing needs no rewrite, whatever else is happening. */
    @Test
    fun anUnchangedWriteRewritesNothing() {
        val row = pending(
            id = 1,
            change = SettingsChange.SetApps(apps = listOf(instagram, youtube), removed = emptySet()),
            applyAt = 2_000L,
        )

        val rewrites = listOf(row).rewritesAfterAppWrite(
            before = listOf(instagram, youtube),
            written = listOf(instagram, youtube),
            nowWall = 1_000L,
            live = true,
        )

        assertTrue(rewrites.isEmpty())
    }

    /** A row written before the removal record existed is still not allowed to re-add a removed app. */
    @Test
    fun aRowWithNoRecordStillLosesWhatALandedRowRemoved() {
        val legacy = pending(
            id = 1,
            change = SettingsChange.SetApps(apps = listOf(instagram, youtube)),
            applyAt = 2_000L,
        )

        val rewrites = listOf(legacy).rewritesAfterAppWrite(
            before = listOf(instagram, youtube),
            written = listOf(youtube),
            nowWall = 1_000L,
            live = false,
        )

        val replacement = rewrites.single().replacement
        assertEquals(setOf(YOUTUBE), packages(replacement.apps))
        assertNull(replacement.removed, "it keeps its \"no record\" meaning")
    }

    @Test
    fun aChangeThatIsNotAnAppListIsNeverRewritten() {
        val row = pending(1, SettingsChange.SetMode(Mode.GENTLE), 2_000L)

        val rewrites = listOf(row).rewritesAfterAppWrite(
            before = listOf(instagram, youtube),
            written = listOf(youtube),
            nowWall = 1_000L,
            live = false,
        )

        assertTrue(rewrites.isEmpty())
    }

    /**
     * The whole of A5-02, end to end, with the two rows composed in the order they land.
     *
     * A removes Instagram. B is queued while Instagram is still in the table and removes TikTok. A
     * lands, then B does: the table must end with YouTube alone. Before the fix Instagram came back,
     * because B's snapshot counted it as an addition.
     */
    @Test
    fun twoQueuedRowsLandInOrderWithoutUndoingEachOther() {
        val table = listOf(instagram, tiktok, youtube)
        val rowA = SettingsChange.SetApps(apps = listOf(tiktok, youtube), removed = setOf(INSTAGRAM))
        val rowB = SettingsChange.SetApps(apps = listOf(instagram, youtube), removed = setOf(TIKTOK))

        val afterA = rowA.rebasedOnto(table)
        val composedB = listOf(pending(2, rowB, applyAt = 2_000L))
            .rewritesAfterAppWrite(table, afterA, nowWall = 1_000L, live = false)
            .single()
            .replacement
        val afterB = composedB.rebasedOnto(afterA)

        assertEquals(setOf(TIKTOK, YOUTUBE), packages(afterA))
        assertEquals(
            setOf(YOUTUBE),
            packages(afterB),
            "both removals happen, and neither row puts back what the other one removed",
        )
    }

    /**
     * The other ordering, in one pass: both rows are due together, so the applier composes them by
     * striking the first row's removals out of the second row's snapshot before it is applied.
     */
    @Test
    fun aDueBatchComposesTheSameWayAsTwoPasses() {
        val table = listOf(instagram, tiktok, youtube)
        val rowA = SettingsChange.SetApps(apps = listOf(tiktok, youtube), removed = setOf(INSTAGRAM))
        val rowB = SettingsChange.SetApps(apps = listOf(instagram, youtube), removed = setOf(TIKTOK))

        val afterA = rowA.rebasedOnto(table)
        val composedB = rowB.copy(apps = rowB.apps.filterNot { it.packageName in setOf(INSTAGRAM) })
        val afterB = composedB.rebasedOnto(afterA)

        assertEquals(setOf(YOUTUBE), packages(afterB))
    }
}
