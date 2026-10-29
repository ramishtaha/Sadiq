package com.ramish.sadiq.strict

import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.core.model.GuardConfig
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

/**
 * The queue's second half: a waiting edit that lands, and one that does not.
 *
 * The repository here is [GatedSettingsRepository], which really does re-ask the gate on every write.
 * That is deliberate: the interesting failure is not "the row was removed" but "the gate deferred the
 * edit again and it never landed", and only a repository with the real write path can catch it.
 */
class PendingChangeApplierTest {

    private val clock = FakeClock()

    private val gate = ChangeGateImpl(policy = DefaultStrictPolicy(), clock = clock)

    private val pending = FakePendingChangesRepository()

    private val repository = GatedSettingsRepository(
        initial = GuardConfig(
            mode = Mode.BALANCED,
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
