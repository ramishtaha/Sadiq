package com.ramish.sadiq.strict

import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
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
private const val TIKTOK: String = "com.zhiliaoapp.musically"

/** The same four apps as guard entries; the label is decoration and is never asserted here. */
private val instagram = GuardedApp(INSTAGRAM, "Instagram")
private val youtube = GuardedApp(YOUTUBE, "YouTube")
private val reddit = GuardedApp(REDDIT, "Reddit")
private val tiktok = GuardedApp(TIKTOK, "TikTok")

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

    // ── two queued rows (AUDIT-5 A5-01, A5-02, A5-03, A5-10) ───────────────────────────────────

    /**
     * A5-02 end to end, across two passes.
     *
     * Row A removes Instagram. A second save is made while Instagram is still in the table — the way
     * the shipped build made it — and removes TikTok. When A lands, B's snapshot still names
     * Instagram; the age of that snapshot is what used to count it as an addition ten minutes later.
     */
    @Test
    fun twoQueuedAppRowsDoNotUndoEachOther() = runTest {
        val fixture = fixture(
            GuardConfig(
                mode = Mode.BALANCED,
                apps = listOf(instagram, tiktok, youtube),
                strict = StrictConfig(enabled = true, delayMin = 10),
            ),
        )

        // 23:00 — un-ticking Instagram loosens, so it waits.
        assertIs<ApplyResult.Scheduled>(
            fixture.repository.apply(SettingsChange.SetApps(listOf(tiktok, youtube))),
        )
        assertEquals(setOf(INSTAGRAM, TIKTOK, YOUTUBE), fixture.guarded())

        // 23:02 — he also un-ticks TikTok. The row is recorded the way the build that shipped wrote
        // it: its snapshot still holds Instagram, and it removes only TikTok.
        fixture.clock.advance(wallMinutes = 2L)
        fixture.pending.add(
            SettingsChange.SetApps(listOf(instagram, youtube), removed = setOf(TIKTOK)),
            applyAt = fixture.clock.now().wallMs + 10 * 60_000L,
        )

        // 23:11 — only the first row is due.
        fixture.clock.advance(wallMinutes = 9L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(
            setOf(TIKTOK, YOUTUBE),
            fixture.guarded(),
            "the first row does its own removal and nothing else",
        )
        val waiting = fixture.pending.all.single().change as SettingsChange.SetApps
        assertEquals(
            setOf(YOUTUBE),
            waiting.apps.mapTo(mutableSetOf()) { app -> app.packageName },
            "the waiting snapshot no longer names what the landing row removed",
        )

        // 23:14 — the second row lands. Instagram must not come back.
        fixture.clock.advance(wallMinutes = 3L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(setOf(YOUTUBE), fixture.guarded())
        assertTrue(fixture.pending.all.isEmpty())
    }

    /**
     * The same two rows landing in one pass, which is what happens when the phone is not opened for
     * a while: the applier has to compose the batch itself, because the repository's snapshot work
     * skips rows that are already due.
     */
    @Test
    fun aBatchOfDueAppRowsIsComposedInOrder() = runTest {
        val fixture = fixture(
            GuardConfig(
                mode = Mode.BALANCED,
                apps = listOf(instagram, tiktok, youtube),
                strict = StrictConfig(enabled = true, delayMin = 10),
            ),
        )

        fixture.repository.apply(SettingsChange.SetApps(listOf(tiktok, youtube)))
        fixture.clock.advance(wallMinutes = 2L)
        fixture.pending.add(
            SettingsChange.SetApps(listOf(instagram, youtube), removed = setOf(TIKTOK)),
            applyAt = fixture.clock.now().wallMs + 10 * 60_000L,
        )

        fixture.clock.advance(wallMinutes = 12L)
        assertEquals(2, fixture.applier.applyDue(), "both rows were due in one pass")
        assertEquals(
            setOf(YOUTUBE),
            fixture.guarded(),
            "the second row must not read the first one's removal as an addition of its own",
        )
    }

    /**
     * A5-03: a landing row used to be compared against its own stale snapshot, so a queued removal of
     * an app added after that snapshot was deleted. The row must survive and do its work.
     */
    @Test
    fun aLandingRowDoesNotDropALaterQueuedRemoval() = runTest {
        val fixture = fixture(
            GuardConfig(
                mode = Mode.BALANCED,
                apps = listOf(instagram, youtube),
                strict = StrictConfig(enabled = true, delayMin = 10),
            ),
        )

        // 23:00 — Instagram waits to go.
        fixture.repository.apply(SettingsChange.SetApps(listOf(youtube)))

        // 23:02 — Reddit is added. Adding tightens, so it lands at once.
        fixture.clock.advance(wallMinutes = 2L)
        assertEquals(
            ApplyResult.Applied,
            fixture.repository.apply(SettingsChange.SetApps(listOf(instagram, youtube, reddit))),
        )

        // 23:03 — he changes his mind about Reddit. That waits too.
        fixture.clock.advance(wallMinutes = 1L)
        assertIs<ApplyResult.Scheduled>(
            fixture.repository.apply(SettingsChange.SetApps(listOf(instagram, youtube))),
        )
        assertEquals(2, fixture.pending.all.size)

        // 23:11 — the first row lands. Its snapshot never heard of Reddit, but the list it wrote
        // does guard it, so the second row still has a removal to make.
        fixture.clock.advance(wallMinutes = 8L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(
            1,
            fixture.pending.all.size,
            "the queued removal of Reddit must not be dropped by a snapshot that predates it",
        )

        // 23:14 — it lands, and Reddit goes.
        fixture.clock.advance(wallMinutes = 3L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(setOf(YOUTUBE), fixture.guarded())
    }

    /**
     * A5-01: an app he re-added after a removal landed must stay guarded when the second row — the
     * one that recorded the same removal again — finally lands.
     */
    @Test
    fun aQueuedRowDoesNotUnGuardAnAppHeReAdded() = runTest {
        val fixture = fixture(
            GuardConfig(
                mode = Mode.BALANCED,
                apps = listOf(instagram, tiktok, youtube),
                strict = StrictConfig(enabled = true, delayMin = 10),
            ),
        )

        // 23:00 — Instagram waits to go.
        fixture.repository.apply(SettingsChange.SetApps(listOf(tiktok, youtube)))

        // 23:02 — the second save of the shipped build: Instagram and TikTok were both un-ticked,
        // because the picker still showed Instagram as ticked.
        fixture.clock.advance(wallMinutes = 2L)
        fixture.pending.add(
            SettingsChange.SetApps(listOf(tiktok, youtube), removed = setOf(INSTAGRAM, TIKTOK)),
            applyAt = fixture.clock.now().wallMs + 10 * 60_000L,
        )

        // 23:11 — the first row lands, and Instagram is gone.
        fixture.clock.advance(wallMinutes = 9L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(setOf(TIKTOK, YOUTUBE), fixture.guarded())

        // 23:12 — he changes his mind and ticks Instagram again. That tightens, so it lands at once,
        // and it calls off the queued removal that would otherwise undo it.
        fixture.clock.advance(wallMinutes = 1L)
        assertEquals(
            ApplyResult.Applied,
            fixture.repository.apply(SettingsChange.SetApps(listOf(instagram, tiktok, youtube))),
        )
        assertEquals(setOf(INSTAGRAM, TIKTOK, YOUTUBE), fixture.guarded())

        // 23:15 — the second row lands. It still drops TikTok, and must leave Instagram alone.
        fixture.clock.advance(wallMinutes = 3L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(
            setOf(INSTAGRAM, YOUTUBE),
            fixture.guarded(),
            "he guarded Instagram a minute after the first row landed; nothing may take it away",
        )
    }

    /**
     * A5-10: a queued "Ramadan off" must survive a Hijri-offset correction, which is a different
     * field of the same type and lands at once because it is neutral.
     */
    @Test
    fun aQueuedModeChangeSurvivesAnOffsetCorrection() = runTest {
        val fixture = fixture(
            GuardConfig(
                mode = Mode.BALANCED,
                apps = listOf(instagram, youtube),
                strict = StrictConfig(enabled = true, delayMin = 10),
                ramadan = RamadanConfig(mode = RamadanMode.ON),
            ),
        )

        // 23:00 — leaving Ramadan mode waits.
        assertIs<ApplyResult.Scheduled>(
            fixture.repository.apply(
                SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF)),
            ),
        )

        // 23:02 — correcting the Hijri offset is neutral, so it lands now.
        fixture.clock.advance(wallMinutes = 2L)
        assertEquals(
            ApplyResult.Applied,
            fixture.repository.apply(
                SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 2)),
            ),
        )
        assertEquals(
            1,
            fixture.pending.all.size,
            "the offset edit does not speak for the mode he is still waiting to change",
        )

        // 23:11 — the mode change lands, and the offset correction is still there.
        fixture.clock.advance(wallMinutes = 9L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(RamadanMode.OFF, fixture.repository.config.value.ramadan.mode)
        assertEquals(2, fixture.repository.config.value.ramadan.hijriOffsetDays)
    }

    /**
     * Two queued field edits of the same type keep each other's fields.
     *
     * He queues "Think it over off" and, while that waits, a shorter delay (both loosening). The
     * first lands: the second must not be deleted — a different field — and it must not carry the
     * old switch back, or it would turn the feature on ten minutes after he turned it off.
     */
    @Test
    fun twoQueuedFieldEditsKeepEachOthersFields() = runTest {
        val fixture = fixture(
            GuardConfig(
                mode = Mode.BALANCED,
                apps = listOf(instagram, youtube),
                strict = StrictConfig(enabled = true, delayMin = 10),
            ),
        )

        // 23:00 — switching the wait off waits.
        assertIs<ApplyResult.Scheduled>(
            fixture.repository.apply(
                SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10)),
            ),
        )

        // 23:02 — a shorter delay is loosening too, so it waits as well.
        fixture.clock.advance(wallMinutes = 2L)
        assertIs<ApplyResult.Scheduled>(
            fixture.repository.apply(
                SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 5)),
            ),
        )
        assertEquals(2, fixture.pending.all.size)

        // 23:11 — the first lands. The second is kept and re-cut: it keeps his 5 minutes and takes
        // the switch the first row turned off.
        fixture.clock.advance(wallMinutes = 9L)
        assertEquals(1, fixture.applier.applyDue())
        val waiting = fixture.pending.all.single().change as SettingsChange.SetStrict
        assertEquals(false, waiting.cfg.enabled, "the switch the row that landed turned off")
        assertEquals(5, waiting.cfg.delayMin, "the delay this row is still waiting to set")
        assertEquals(false, fixture.repository.config.value.strict.enabled)

        // 23:14 — the second lands, and neither edit undoes the other.
        fixture.clock.advance(wallMinutes = 3L)
        assertEquals(1, fixture.applier.applyDue())
        assertEquals(StrictConfig(enabled = false, delayMin = 5), fixture.repository.config.value.strict)
    }

    /**
     * A repository, an applier and a queue for one test, so a two-row scenario can start from the
     * table it needs. The class-level fixture is fixed at `[Instagram, YouTube]`.
     */
    private fun fixture(initial: GuardConfig): Fixture = Fixture(initial)
}

/**
 * One repository, one applier and one queue, wired as `SettingsRepositoryImpl` and
 * `PendingChangeApplier` are in the app.
 *
 * @param initial the settings in force.
 */
private class Fixture(initial: GuardConfig) {

    val clock = FakeClock()

    val gate = ChangeGateImpl(policy = DefaultStrictPolicy(), clock = clock)

    val pending = FakePendingChangesRepository()

    val repository = GatedSettingsRepository(
        initial = initial,
        gate = gate,
        pendingChanges = pending,
        clock = clock,
    )

    val applier = PendingChangeApplier(
        settingsRepository = repository,
        pendingChanges = pending,
        changeGate = gate,
        controller = FakeGuardController(),
        clock = clock,
    )

    /** The guarded packages, which is the only thing these tests are about. */
    fun guarded(): Set<String> =
        repository.config.value.apps.mapTo(mutableSetOf()) { app -> app.packageName }
}
