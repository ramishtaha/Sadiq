package com.ramish.sadiq.data.settings

import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.model.SurfaceMode
import com.ramish.sadiq.core.strict.SettingsChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val INSTAGRAM: String = "com.instagram.android"
private const val TIKTOK: String = "com.zhiliaoapp.musically"
private const val YOUTUBE: String = "com.google.android.youtube"
private const val REDDIT: String = "com.reddit.frontpage"

private val instagram = GuardedApp(INSTAGRAM, "Instagram")
private val youtube = GuardedApp(YOUTUBE, "YouTube")
private val reddit = GuardedApp(REDDIT, "Reddit")

/**
 * Dropping a waiting row only when the newer edit really replaced it (AUDIT-5 A5-03, A5-10).
 *
 * Both failures here delete a row he is waiting for, and both are silent: the line disappears from
 * "Takes effect soon" and the setting never changes. The rule is now the fields an edit actually
 * moves, and — for an app list — the list that was written rather than the snapshot it replaced.
 */
class SupersessionTest {

    // ── A5-10: the same type, a different field ───────────────────────────────────────────────

    /**
     * He queued "Ramadan off", then corrected the Hijri offset. `RamadanSettingsViewModel` builds the
     * offset edit from the current mode, so it is neutral and lands at once — and it used to take the
     * queued "off" with it, because both are `SetRamadan`.
     */
    @Test
    fun aHijriOffsetCorrectionDoesNotDeleteAQueuedRamadanMode() {
        val before = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON))
        val queued = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF))
        val offsetEdit = SettingsChange.SetRamadan(
            RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 2),
        )

        assertFalse(
            queued.isMadePointlessBy(offsetEdit, before, emptyList()),
            "the offset is a calendar correction; the mode he is waiting on is untouched",
        )
    }

    /** The same field does supersede: he turned Ramadan back on, so the queued "off" is pointless. */
    @Test
    fun aRamadanModeEditDoesDeleteAQueuedRamadanMode() {
        val before = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON))
        val queued = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF))
        val modeEdit = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF))

        assertTrue(queued.isMadePointlessBy(modeEdit, before, emptyList()))
    }

    /**
     * He queued "Think it over off" and then changed the delay. A longer delay is tightening, so it
     * lands at once — and it used to delete the queued "off" on its way past.
     */
    @Test
    fun aDelayChangeDoesNotDeleteAQueuedStrictSwitch() {
        val before = GuardConfig(strict = StrictConfig(enabled = true, delayMin = 10))
        val queued = SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10))
        val longerWait = SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 30))

        assertFalse(queued.isMadePointlessBy(longerWait, before, emptyList()))
    }

    @Test
    fun aStrictSwitchEditDoesDeleteAQueuedStrictSwitch() {
        val before = GuardConfig(strict = StrictConfig(enabled = true, delayMin = 10))
        val queued = SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10))
        val sameSwitch = SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10))

        assertTrue(queued.isMadePointlessBy(sameSwitch, before, emptyList()))
    }

    /** A later bedtime that lands at once replaces the queued later bedtime: same field. */
    @Test
    fun aBedtimeEditOnlySupersedesTheBedtimeFieldItMoves() {
        val before = GuardConfig(sleepGate = SleepGateConfig(enabled = true, startMinuteOfDay = 1350))
        val queued = SettingsChange.SetSleepGate(
            SleepGateConfig(enabled = true, startMinuteOfDay = 1440),
        )

        assertTrue(
            queued.isMadePointlessBy(
                SettingsChange.SetSleepGate(SleepGateConfig(enabled = true, startMinuteOfDay = 1200)),
                before,
                emptyList(),
            )
        )
        assertFalse(
            queued.isMadePointlessBy(
                SettingsChange.SetSleepGate(SleepGateConfig(enabled = false, startMinuteOfDay = 1350)),
                before,
                emptyList(),
            ),
            "the switch is a different field, and it moves the window as a whole",
        )
    }

    /** Three independent switches: turning check-ins on does not speak for the web guard. */
    @Test
    fun aFlagEditOnlySupersedesTheFlagItMoves() {
        val before = GuardConfig(reflectionEnabled = true, webGuardEnabled = true)
        val queued = SettingsChange.SetFlags(reflection = null, gymHint = null, webGuard = false)

        assertFalse(
            queued.isMadePointlessBy(
                SettingsChange.SetFlags(reflection = true, gymHint = null, webGuard = null),
                before,
                emptyList(),
            )
        )
        assertTrue(
            queued.isMadePointlessBy(
                SettingsChange.SetFlags(reflection = null, gymHint = null, webGuard = false),
                before,
                emptyList(),
            )
        )
    }

    /** An edit that moves nothing cannot replace anything. */
    @Test
    fun anEditThatChangesNothingSupersedesNothing() {
        val before = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON))
        val queued = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF))
        val noOp = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON))

        assertFalse(queued.isMadePointlessBy(noOp, before, emptyList()))
    }

    /** A single-value type keeps the old rule: any later edit of it replaces the earlier one. */
    @Test
    fun aLaterModeReplacesAQueuedMode() {
        val before = GuardConfig(mode = Mode.BALANCED)

        assertTrue(
            SettingsChange.SetMode(Mode.GENTLE)
                .isMadePointlessBy(SettingsChange.SetMode(Mode.HARDCORE), before, emptyList())
        )
    }

    @Test
    fun aDifferentTypeNeverSupersedes() {
        val before = GuardConfig()

        assertFalse(
            SettingsChange.SetMode(Mode.GENTLE)
                .isMadePointlessBy(SettingsChange.SetApps(emptyList()), before, emptyList())
        )
        assertFalse(
            SettingsChange.SetApps(listOf(youtube))
                .isMadePointlessBy(SettingsChange.SetMode(Mode.HARDCORE), before, emptyList())
        )
    }

    @Test
    fun aSurfaceModeEditOnlySupersedesItsOwnApp() {
        val before = GuardConfig()
        val queued = SettingsChange.SetSurfaceMode(INSTAGRAM, SurfaceMode.SHORTS_ONLY)

        assertTrue(
            queued.isMadePointlessBy(
                SettingsChange.SetSurfaceMode(INSTAGRAM, SurfaceMode.WHOLE_APP),
                before,
                emptyList(),
            )
        )
        assertFalse(
            queued.isMadePointlessBy(
                SettingsChange.SetSurfaceMode(YOUTUBE, SurfaceMode.WHOLE_APP),
                before,
                emptyList(),
            )
        )
    }

    // ── A5-03: judged against the list actually written ────────────────────────────────────────

    /**
     * A5-03's repro. Row A removed Instagram and its snapshot predates Reddit. He added Reddit, then
     * un-ticked it, which queued row B. When A lands the written list still guards Reddit, so B still
     * has a removal to make — but A's stale snapshot does not contain it, and comparing against that
     * deleted B a pass too early.
     */
    @Test
    fun aQueuedRemovalSurvivesTheLandingOfAStaleSnapshot() {
        val before = GuardConfig(apps = listOf(instagram, youtube))
        val landingRow = SettingsChange.SetApps(
            apps = listOf(youtube),
            removed = setOf(INSTAGRAM),
        )
        val queuedRemoval = SettingsChange.SetApps(
            apps = listOf(instagram, youtube),
            removed = setOf(REDDIT),
        )

        val written = listOf(youtube, reddit)

        assertFalse(
            queuedRemoval.isMadePointlessBy(landingRow, before, written),
            "Reddit is still guarded, so the row that drops it still has work to do",
        )
    }

    /** And when the written list really did drop it, the row is dead weight and goes. */
    @Test
    fun aQueuedRemovalIsDroppedOnceTheWrittenListDroppedIt() {
        val before = GuardConfig(apps = listOf(instagram, youtube))
        val landingRow = SettingsChange.SetApps(apps = listOf(youtube), removed = setOf(INSTAGRAM))
        val queuedRemoval = SettingsChange.SetApps(apps = listOf(youtube), removed = setOf(INSTAGRAM))

        assertTrue(
            queuedRemoval.isMadePointlessBy(landingRow, before, listOf(youtube)),
            "Instagram is already gone; replaying the row would say nothing",
        )
    }

    /**
     * A row with no removal record is never dropped this way. It cannot say what it meant to remove,
     * so it is merged when it lands; deleting it would throw his edit away on a comparison that does
     * not mean anything for it.
     */
    @Test
    fun aRowWithNoRemovalRecordIsNeverSupersededByAnAppList() {
        val before = GuardConfig(apps = listOf(instagram, youtube))
        val legacy = SettingsChange.SetApps(apps = listOf(youtube))

        assertFalse(
            legacy.isMadePointlessBy(
                SettingsChange.SetApps(apps = listOf(youtube)),
                before,
                listOf(youtube),
            )
        )
    }

    /** The fields are named, so two edits can be compared without guessing at their type. */
    @Test
    fun theChangedFieldsAreTheOnesThatMoved() {
        val before = GuardConfig(
            ramadan = RamadanConfig(mode = RamadanMode.ON),
            strict = StrictConfig(enabled = true, delayMin = 10),
        )

        assertEquals(
            setOf("ramadan.mode"),
            SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF)).changedFieldsIn(before),
        )
        assertEquals(
            setOf("ramadan.hijriOffsetDays"),
            SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = -1))
                .changedFieldsIn(before),
        )
        assertEquals(
            setOf("strict.enabled", "strict.delayMin"),
            SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 20))
                .changedFieldsIn(before),
        )
    }

    // ── re-cutting the row that survives ───────────────────────────────────────────────────────

    /**
     * Keeping the row is not enough on its own: it holds a whole `RamadanConfig`, so replaying it
     * would put the offset he corrected a minute ago back to zero. The fields it moved keep its
     * values; the rest take the write that landed.
     */
    @Test
    fun aQueuedRamadanModeKeepsTheOffsetHeJustCorrected() {
        val before = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON))
        val queued = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF))
        val offsetEdit = SettingsChange.SetRamadan(
            RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 2),
        )

        val recut = queued.rebasedBehind(offsetEdit, before) as SettingsChange.SetRamadan

        assertEquals(RamadanMode.OFF, recut.cfg.mode, "the field he is waiting to change")
        assertEquals(2, recut.cfg.hijriOffsetDays, "the field he just corrected")
    }

    @Test
    fun aQueuedStrictSwitchKeepsTheDelayHeJustChose() {
        val before = GuardConfig(strict = StrictConfig(enabled = true, delayMin = 10))
        val queued = SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10))
        val longerWait = SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 30))

        val recut = queued.rebasedBehind(longerWait, before) as SettingsChange.SetStrict

        assertEquals(false, recut.cfg.enabled)
        assertEquals(30, recut.cfg.delayMin)
    }

    @Test
    fun aQueuedFlagKeepsTheFlagItMovedAndTakesTheRest() {
        val before = GuardConfig(reflectionEnabled = true, gymHintEnabled = true, webGuardEnabled = true)
        val queued = SettingsChange.SetFlags(reflection = null, gymHint = null, webGuard = false)
        val training = SettingsChange.SetFlags(reflection = true, gymHint = false, webGuard = null)

        val recut = queued.rebasedBehind(training, before) as SettingsChange.SetFlags

        assertEquals(false, recut.webGuard, "the flag it moved")
        assertEquals(false, recut.gymHint, "the flag the newer edit moved")
        assertEquals(true, recut.reflection)
    }

    /** A row that is not the same type is not re-cut: there is nothing to merge it with. */
    @Test
    fun aRowOfAnotherTypeIsLeftExactlyAsItWas() {
        val before = GuardConfig(ramadan = RamadanConfig(mode = RamadanMode.ON))
        val queued = SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF))

        val recut = queued.rebasedBehind(SettingsChange.SetMode(Mode.GENTLE), before)

        assertSame(queued, recut)
    }
}
