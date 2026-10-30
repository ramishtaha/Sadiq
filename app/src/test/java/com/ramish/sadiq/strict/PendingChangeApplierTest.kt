package com.ramish.sadiq.strict

import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.strict.DefaultStrictPolicy
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.testing.FakeClock
import com.ramish.sadiq.testing.FakeGuardController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val INSTAGRAM: String = "com.instagram.android"
private const val REDDIT: String = "com.reddit.frontpage"
private const val YOUTUBE: String = "com.google.android.youtube"

/** The same three apps as guard entries; the label is decoration and is never asserted here. */
private val instagram = GuardedApp(INSTAGRAM, "Instagram")
private val youtube = GuardedApp(YOUTUBE, "YouTube")
private val reddit = GuardedApp(REDDIT, "Reddit")

/**
 * The queue's second half: a waiting edit that lands, and one that does not.
 *
 * The repository here is [GatedSettingsRepository], which really does re-ask the gate on every write.
 * That is deliberate: the interesting failure is not "the row was removed" but "the gate deferred the
 * edit again and it never landed", and only a repository with the real write path can catch it.
 *
 * The first four tests are AUDIT-4 A4-12: a queued `SetApps` is a full list, and the list it was
 * built from is not the list it lands on.
 */
class PendingChangeApplierTest {

    private val clock = FakeClock()

    private val gate = ChangeGateImpl(policy = DefaultStrictPolicy(), clock = clock)

    private val pending = FakePendingChangesRepository()

    private val repository = GatedSettingsRepository(
        initial = GuardConfig(
            mode = Mode.BALANCED,
            apps = listOf(instagram, youtube),
            strict = StrictConfig(enabled = true, delayMin = 10)
        ),
        gate = gate,
        pendingChanges = pending,
        clock = clock
    )

    private val applier = PendingChangeApplier(
        settingsRepository = repository,
        pendingChanges = pending,
        changeGate = gate,
        controller = FakeGuardController(),
        clock = clock
    )

    /** The guarded packages, which is the only thing A4-12 is about. */
    private fun guarded(): Set<String> =
        repository.config.value.apps.mapTo(mutableSetOf()) { app -> app.packageName }

    /**
     * The audit's scenario, end to end: un-tick Instagram (waits), add Reddit (lands at once),
     * come back ten minutes later.
     *
     * Before the fix the queued snapshot was written verbatim, so the moment it landed the table
     * held `[YouTube]`: Reddit, which had been guarded for ten minutes and which the user had never
     * asked to drop, silently stopped being guarded. Nothing on screen said so.
     */
    @Test
    fun aQueuedSnapshotDoesNotUnguardAnAppAddedWhileItWaited() = runTest {
        // 1. Removing an app loosens, so it waits.
        val queued = repository.apply(SettingsChange.SetApps(listOf(youtube)))
        assertIs<ApplyResult.Scheduled>(queued)
        assertEquals(setOf(INSTAGRAM, YOUTUBE), guarded(), "the removal must not land at once")

        // 2. Adding Reddit tightens, so it lands now. The queued row is kept, because the new list
        //    still guards Instagram and the removal he asked for has not happened yet.
        val added = repository.apply(SettingsChange.SetApps(listOf(instagram, youtube, reddit)))
        assertEquals(ApplyResult.Applied, added)
        assertEquals(setOf(INSTAGRAM, YOUTUBE, REDDIT), guarded())
        assertEquals(
            1,
            pending.all.size,
            "the newer list keeps Instagram, so the queue still has work",
        )

        // 3. Ten minutes later the snapshot lands.
        clock.advance(wallMinutes = 11L)
        assertEquals(1, applier.applyDue())

        assertEquals(
            setOf(YOUTUBE, REDDIT),
            guarded(),
            "only Instagram may go: Reddit was added while the snapshot waited",
        )
        assertTrue(pending.all.isEmpty(), "an applied change leaves the queue")
    }

    /**
     * A row from a build that did not record removals cannot say what it meant to remove, so it is
     * merged rather than written back. It may leave an app guarded that the user dropped — visible,
     * and one tap to redo — but it must never drop one he added while it waited.
     */
    @Test
    fun aRowWithNoRemovalRecordMergesInsteadOfReplacing() = runTest {
        pending.add(
            SettingsChange.SetApps(listOf(youtube)),
            applyAt = clock.now().wallMs + 10 * 60_000L,
        )
        clock.advance(wallMinutes = 11L)

        assertEquals(1, applier.applyDue())

        assertEquals(
            setOf(INSTAGRAM, YOUTUBE),
            guarded(),
            "a row with no record subtracts nothing",
        )
    }

    /** A save that drops one app and adds another must do both when it lands. */
    @Test
    fun aQueuedSnapshotStillAddsTheAppsItMeantToAdd() = runTest {
        val queued = repository.apply(SettingsChange.SetApps(listOf(youtube, reddit)))
        assertIs<ApplyResult.Scheduled>(
            queued,
            "removal wins inside one list, so the whole save waits",
        )

        clock.advance(wallMinutes = 11L)
        assertEquals(1, applier.applyDue())

        assertEquals(setOf(YOUTUBE, REDDIT), guarded())
    }

    /**
     * The other half of A4-12: a queued edit of the same kind that a newer one has replaced must be
     * dropped. Without it a queued `SetMode(GENTLE)` undoes the Firm he chose while it waited, ten
     * minutes after he chose it.
     */
    @Test
    fun aQueuedEditIsDroppedWhenANewerEditOfTheSameKindLands() = runTest {
        repository.apply(SettingsChange.SetMode(Mode.GENTLE))
        assertEquals(1, pending.all.size)

        assertEquals(ApplyResult.Applied, repository.apply(SettingsChange.SetMode(Mode.HARDCORE)))
        assertTrue(
            pending.all.isEmpty(),
            "the queued Gentle would have undone the Firm he chose while it waited",
        )

        clock.advance(wallMinutes = 11L)
        assertEquals(0, applier.applyDue())
        assertEquals(Mode.HARDCORE, repository.config.value.mode)
    }

    @Test
    fun aLooseningEditIsStoredAndThenLandsWhenItsTimeComes() = runTest {
        val result = repository.apply(SettingsChange.SetMode(Mode.GENTLE))

        assertIs<ApplyResult.Scheduled>(result)
        assertEquals(Mode.BALANCED, repository.config.value.mode, "a loosening edit must not land at once")
        assertEquals(1, pending.all.size, "the edit is visible in Guard while it waits")

        clock.advance(wallMinutes = 11L)

        assertEquals(1, applier.applyDue())
        assertEquals(Mode.GENTLE, repository.config.value.mode)
        assertTrue(pending.all.isEmpty(), "an applied change leaves the queue")
    }

    @Test
    fun aChangeThatIsNotDueYetIsLeftAlone() = runTest {
        repository.apply(SettingsChange.SetMode(Mode.GENTLE))

        clock.advance(wallMinutes = 5L)

        assertEquals(0, applier.applyDue())
        assertEquals(Mode.BALANCED, repository.config.value.mode)
        assertEquals(1, pending.all.size)
    }

    @Test
    fun aCancelledChangeIsNeverApplied() = runTest {
        repository.apply(SettingsChange.SetMode(Mode.GENTLE))
        val queued = pending.all.single()

        pending.remove(queued.id)
        clock.advance(wallMinutes = 60L)

        assertEquals(0, applier.applyDue())
        assertEquals(Mode.BALANCED, repository.config.value.mode)
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun aTighteningEditNeverEntersTheQueue() = runTest {
        val result = repository.apply(SettingsChange.SetMode(Mode.HARDCORE))

        assertEquals(ApplyResult.Applied, result)
        assertEquals(Mode.HARDCORE, repository.config.value.mode)
        assertTrue(pending.all.isEmpty())
    }

    @Test
    fun applyingTwiceDoesNotApplyTheSameRowTwice() = runTest {
        repository.apply(SettingsChange.SetMode(Mode.GENTLE))
        clock.advance(wallMinutes = 11L)

        assertEquals(1, applier.applyDue())
        assertEquals(0, applier.applyDue(), "the row was removed, so the second pass has nothing to do")
        assertEquals(1, repository.applied.count { change -> change is SettingsChange.SetMode })
    }

    @Test
    fun aFailedWriteLeavesTheRowQueued() = runTest {
        repository.apply(SettingsChange.SetMode(Mode.GENTLE))
        clock.advance(wallMinutes = 11L)
        repository.failure = IllegalStateException("the disk is full")

        assertEquals(0, applier.applyDue())
        assertEquals(1, pending.all.size, "a failed write must not lose the edit")
        assertEquals(Mode.BALANCED, repository.config.value.mode)

        repository.failure = null
        assertEquals(1, applier.applyDue(), "the next pass picks it up again")
        assertEquals(Mode.GENTLE, repository.config.value.mode)
    }
}
