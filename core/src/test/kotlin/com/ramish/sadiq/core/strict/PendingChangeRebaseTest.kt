package com.ramish.sadiq.core.strict

import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.SurfaceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rebase rule of AUDIT-4 A4-12, asserted on its own.
 *
 * `SettingsRepositoryImpl` and the applier's tests both run this code, but the arithmetic is the
 * thing that has to be right, so it is pinned here without a database, a gate or a clock. Every case
 * is one of the ways the old "write the snapshot back verbatim" behaviour went wrong.
 */
class PendingChangeRebaseTest {

    private val instagram = GuardedApp("com.instagram.android", "Instagram")
    private val youtube = GuardedApp("com.google.android.youtube", "YouTube")
    private val reddit = GuardedApp("com.reddit.frontpage", "Reddit")
    private val tiktok = GuardedApp("com.zhiliaoapp.musically", "TikTok")

    private fun packages(apps: List<GuardedApp>): Set<String> =
        apps.mapTo(mutableSetOf()) { app -> app.packageName }

    // ── rebasing a queued snapshot ──────────────────────────────────────────────────────────────

    /**
     * The audit's scenario: the snapshot was made when Instagram was the only removal, and Reddit
     * arrived while it waited. The snapshot may take Instagram and nothing else.
     */
    @Test
    fun theSnapshotTakesOnlyWhatItMeantToRemove() {
        val queued = SettingsChange.SetApps(
            apps = listOf(youtube),
            removed = setOf(instagram.packageName),
        )

        val written = queued.rebasedOnto(listOf(instagram, youtube, reddit))

        assertEquals(
            setOf(youtube.packageName, reddit.packageName),
            packages(written),
            "Reddit was added while the snapshot waited and must stay guarded",
        )
    }

    /**
     * A row written before the removal record existed cannot say what it removed. Subtracting
     * nothing is the only safe reading: it may leave an app guarded that the user dropped, which he
     * can see, but it can never drop one he added.
     */
    @Test
    fun aRowWithNoRecordSubtractsNothing() {
        assertNull(SettingsChange.SetApps(listOf(youtube)).removed, "the default records nothing")

        val written = SettingsChange.SetApps(listOf(youtube)).rebasedOnto(listOf(instagram, youtube))

        assertEquals(setOf(instagram.packageName, youtube.packageName), packages(written))
    }

    /** One save can drop an app and add another; both halves of it still happen, ten minutes late. */
    @Test
    fun aSnapshotStillAddsTheAppsItMeantToAdd() {
        val queued = SettingsChange.SetApps(
            apps = listOf(youtube, reddit),
            removed = setOf(instagram.packageName),
        )

        val written = queued.rebasedOnto(listOf(instagram, youtube))

        assertEquals(setOf(youtube.packageName, reddit.packageName), packages(written))
    }

    /**
     * The live row wins for an app that is in both lists, so a snapshot cannot revert a
     * `SetSurfaceMode` the user made while it waited — that would be a loosening nobody asked for,
     * ten minutes after the tightening landed.
     */
    @Test
    fun theLiveSurfaceModeWinsOverTheSnapshots() {
        val narrowed = instagram.copy(surfaceMode = SurfaceMode.SHORTS_ONLY)
        val queued = SettingsChange.SetApps(
            apps = listOf(instagram.copy(surfaceMode = SurfaceMode.WHOLE_APP)),
            removed = emptySet(),
        )

        val written = queued.rebasedOnto(listOf(narrowed, youtube))

        val live = written.single { app -> app.packageName == instagram.packageName }
        assertEquals(SurfaceMode.SHORTS_ONLY, live.surfaceMode)
    }

    /** Adding Reddit must not lose Instagram's place, and the merge is stable for equal input. */
    @Test
    fun theMergeIsStableForEqualInput() {
        val snapshot = SettingsChange.SetApps(listOf(youtube), removed = emptySet())
        val current = listOf(instagram, youtube)

        assertEquals(current, snapshot.rebasedOnto(current))
    }

    // ── recording the removals when the edit is queued ──────────────────────────────────────────

    @Test
    fun theRecordIsWhatTheSnapshotDropped() {
        val queued = SettingsChange.SetApps(listOf(youtube)).withRemovalsRecorded(
            listOf(instagram, youtube, tiktok)
        )

        assertEquals(
            setOf(instagram.packageName, tiktok.packageName),
            queued.removed,
            "the two apps the edit drops, and nothing else",
        )
    }

    @Test
    fun aTighteningEditRecordsNoRemovalsButStillRecordsThatItWasQueued() {
        val queued = SettingsChange.SetApps(listOf(instagram, youtube, reddit))
            .withRemovalsRecorded(listOf(instagram, youtube))

        assertEquals(
            emptySet<String>(),
            queued.removed,
            "nothing disappeared, so nothing is subtracted",
        )
    }

    @Test
    fun aSecondDeferralKeepsTheFirstRecord() {
        val first = SettingsChange.SetApps(listOf(youtube), removed = setOf(instagram.packageName))

        val second = first.withRemovalsRecorded(listOf(youtube, instagram))

        assertEquals(
            setOf(instagram.packageName),
            second.removed,
            "a row deferred twice must not forget what the first deferral claimed",
        )
    }

    // ── dropping a queued row a newer edit has replaced ─────────────────────────────────────────

    @Test
    fun aNewerListThatAlreadyDroppedTheRemovalSupersedesTheRow() {
        val queued = SettingsChange.SetApps(listOf(youtube), removed = setOf(instagram.packageName))

        assertTrue(queued.isSupersededBy(SettingsChange.SetApps(listOf(youtube, reddit))))
    }

    /**
     * The audit's own ordering: the newer list still guards Instagram, so the queued row has a
     * removal left to make and keeps its moment. Dropping it here would silently lose the un-tick.
     */
    @Test
    fun aNewerListThatStillGuardsTheRemovalLeavesTheRowAlone() {
        val queued = SettingsChange.SetApps(listOf(youtube), removed = setOf(instagram.packageName))

        assertFalse(
            queued.isSupersededBy(SettingsChange.SetApps(listOf(instagram, youtube, reddit)))
        )
    }

    @Test
    fun aNewerModeSupersedesAQueuedMode() {
        assertTrue(
            SettingsChange.SetMode(Mode.GENTLE).isSupersededBy(SettingsChange.SetMode(Mode.HARDCORE))
        )
    }

    @Test
    fun aSurfaceModeEditOnlySupersedesItsOwnApp() {
        val queued = SettingsChange.SetSurfaceMode(instagram.packageName, SurfaceMode.SHORTS_ONLY)

        assertTrue(
            queued.isSupersededBy(
                SettingsChange.SetSurfaceMode(instagram.packageName, SurfaceMode.WHOLE_APP)
            )
        )
        assertFalse(
            queued.isSupersededBy(
                SettingsChange.SetSurfaceMode(youtube.packageName, SurfaceMode.WHOLE_APP)
            )
        )
    }

    /** Different kinds edit different things, so neither can make the other pointless. */
    @Test
    fun aDifferentKindNeverSupersedes() {
        assertFalse(
            SettingsChange.SetMode(Mode.GENTLE).isSupersededBy(SettingsChange.SetApps(emptyList()))
        )
        assertFalse(
            SettingsChange.SetApps(listOf(youtube)).isSupersededBy(SettingsChange.SetMode(Mode.HARDCORE))
        )
    }
}
