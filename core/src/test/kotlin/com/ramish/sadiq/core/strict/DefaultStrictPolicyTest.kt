package com.ramish.sadiq.core.strict

import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.model.SurfaceMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The classification table of WP-27, branch by branch.
 *
 * One test per [SettingsChange] subtype, because the promise the user reads — "loosening a rule waits
 * ten minutes" — is only as true as the weakest branch. Every case asserts both the direction the
 * table claims and the neutral cases, since a mis-classified neutral is what lets an edit slip
 * through without a wait it should have had.
 */
class DefaultStrictPolicyTest {

    private val policy = DefaultStrictPolicy()

    private val instagram = GuardedApp("com.instagram.android", "Instagram")
    private val tiktok = GuardedApp("com.zhiliaoapp.musically", "TikTok")

    /** Balanced, two guarded apps, web guard on, Think it over on with the plan's ten minutes. */
    private val base = GuardConfig(
        mode = Mode.BALANCED,
        apps = listOf(instagram, tiktok),
        domains = setOf("x.com", "youtube.com"),
        strict = StrictConfig(enabled = true, delayMin = 10),
        webGuardEnabled = true,
    )

    private fun classify(change: SettingsChange, current: GuardConfig = base): ChangeClass =
        policy.classify(current, change)

    // ── Mode ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aGentlerModeLoosens() {
        assertEquals(ChangeClass.LOOSENING, classify(SettingsChange.SetMode(Mode.GENTLE)))
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetMode(Mode.GENTLE), base.copy(mode = Mode.HARDCORE)),
        )
    }

    @Test
    fun aFirmerModeTightens() {
        assertEquals(ChangeClass.TIGHTENING, classify(SettingsChange.SetMode(Mode.HARDCORE)))
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetMode(Mode.BALANCED), base.copy(mode = Mode.GENTLE)),
        )
    }

    @Test
    fun theModeThatIsAlreadyOnIsNeutral() {
        assertEquals(ChangeClass.NEUTRAL, classify(SettingsChange.SetMode(Mode.BALANCED)))
    }

    // ── Guarded apps ───────────────────────────────────────────────────────────────────────────

    @Test
    fun removingAGuardedAppLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetApps(listOf(instagram))),
        )
    }

    @Test
    fun addingAGuardedAppTightens() {
        val youtube = GuardedApp("com.google.android.youtube", "YouTube")
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetApps(listOf(instagram, tiktok, youtube))),
        )
    }

    @Test
    fun anUnchangedAppListIsNeutralEvenWhenALabelChanges() {
        val renamed = instagram.copy(label = "Instagram (beta)")
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetApps(listOf(renamed, tiktok))),
        )
    }

    @Test
    fun aSwapCountsAsARemovalBecauseAnAppIsNoLongerGuarded() {
        val youtube = GuardedApp("com.google.android.youtube", "YouTube")
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetApps(listOf(tiktok, youtube))),
        )
    }

    @Test
    fun narrowingAnAppToShortsThroughTheFullListLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetApps(listOf(instagram.copy(surfaceMode = SurfaceMode.SHORTS_ONLY), tiktok))),
        )
    }

    @Test
    fun guardingTheWholeAppThroughTheFullListTightens() {
        val shortsOnly = base.copy(apps = listOf(instagram.copy(surfaceMode = SurfaceMode.SHORTS_ONLY), tiktok))
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetApps(listOf(instagram, tiktok)), shortsOnly),
        )
    }

    // ── One app's surface ──────────────────────────────────────────────────────────────────────

    @Test
    fun narrowingAGuardedAppToShortsLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetSurfaceMode(instagram.packageName, SurfaceMode.SHORTS_ONLY)),
        )
    }

    @Test
    fun guardingTheWholeAppTightens() {
        val shortsOnly = base.copy(apps = listOf(instagram.copy(surfaceMode = SurfaceMode.SHORTS_ONLY), tiktok))
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetSurfaceMode(instagram.packageName, SurfaceMode.WHOLE_APP), shortsOnly),
        )
    }

    @Test
    fun theSameSurfaceIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetSurfaceMode(instagram.packageName, SurfaceMode.WHOLE_APP)),
        )
    }

    @Test
    fun anUnguardedAppsSurfaceIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetSurfaceMode("com.example.unused", SurfaceMode.SHORTS_ONLY)),
        )
    }

    // ── Domains ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun removingADomainLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetDomains(setOf("x.com"))),
        )
    }

    @Test
    fun addingADomainTightens() {
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetDomains(setOf("x.com", "youtube.com", "reddit.com"))),
        )
    }

    @Test
    fun anUnchangedDomainSetIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetDomains(setOf("youtube.com", "x.com"))),
        )
    }

    @Test
    fun domainsAreInertWhileWebGuardIsOff() {
        val off = base.copy(webGuardEnabled = false)
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetDomains(setOf("x.com", "youtube.com", "reddit.com")), off),
        )
    }

    // ── Bedtime ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun switchingTheBedtimeGateOffLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetSleepGate(SleepGateConfig(enabled = false, startMinuteOfDay = 1350))),
        )
    }

    @Test
    fun switchingTheBedtimeGateOnTightensEvenWithALaterStart() {
        val off = base.copy(sleepGate = SleepGateConfig(enabled = false, startMinuteOfDay = 1350))
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetSleepGate(SleepGateConfig(enabled = true, startMinuteOfDay = 1380)), off),
        )
    }

    @Test
    fun aLaterBedtimeStartLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetSleepGate(SleepGateConfig(enabled = true, startMinuteOfDay = 1380))),
        )
    }

    @Test
    fun anEarlierBedtimeStartTightens() {
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetSleepGate(SleepGateConfig(enabled = true, startMinuteOfDay = 1320))),
        )
    }

    @Test
    fun theSameBedtimeStartIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetSleepGate(SleepGateConfig(enabled = true, startMinuteOfDay = 1350))),
        )
    }

    @Test
    fun movingABedtimeThatIsOffIsNeutral() {
        val off = base.copy(sleepGate = SleepGateConfig(enabled = false, startMinuteOfDay = 1350))
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetSleepGate(SleepGateConfig(enabled = false, startMinuteOfDay = 1380)), off),
        )
    }

    // ── Morning ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun switchingTheMorningOffLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetMorning(MorningConfig(enabled = false, endMinuteOfDay = 570))),
        )
    }

    @Test
    fun switchingTheMorningOnTightensEvenWithAnEarlierEnd() {
        val off = base.copy(morning = MorningConfig(enabled = false, endMinuteOfDay = 570))
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetMorning(MorningConfig(enabled = true, endMinuteOfDay = 540)), off),
        )
    }

    @Test
    fun anEarlierMorningEndLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetMorning(MorningConfig(enabled = true, endMinuteOfDay = 540))),
        )
    }

    @Test
    fun aLaterMorningEndTightens() {
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetMorning(MorningConfig(enabled = true, endMinuteOfDay = 600))),
        )
    }

    @Test
    fun theSameMorningIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetMorning(MorningConfig(enabled = true, endMinuteOfDay = 570))),
        )
    }

    // ── Prayer inputs ──────────────────────────────────────────────────────────────────────────

    @Test
    fun prayerInputsAreNeverStrictness() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetPrayer(PrayerConfig(method = CalcMethod.MUSLIM_WORLD_LEAGUE))),
        )
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetPrayer(PrayerConfig(windowMin = 15))),
            "a shorter prayer window is a different reminder, not a weaker guard",
        )
    }

    // ── Ramadan ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun turningRamadanOnTightens() {
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON))),
        )
    }

    @Test
    fun leavingRamadanLoosens() {
        val on = base.copy(ramadan = RamadanConfig(mode = RamadanMode.ON))
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.AUTO_SUGGEST)), on),
        )
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.OFF)), on),
        )
    }

    @Test
    fun movingBetweenOffAndTheOfferIsNeutral() {
        val off = base.copy(ramadan = RamadanConfig(mode = RamadanMode.OFF))
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.AUTO_SUGGEST)), off),
            "auto-suggest only offers a card; it never changes the schedule",
        )
    }

    @Test
    fun correctingTheHijriOffsetIsNeutral() {
        val on = base.copy(ramadan = RamadanConfig(mode = RamadanMode.ON))
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = -1)), on),
        )
    }

    // ── Think it over itself ───────────────────────────────────────────────────────────────────

    @Test
    fun turningThinkItOverOnTightens() {
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 10)), base.copy(strict = StrictConfig())),
        )
    }

    @Test
    fun turningThinkItOverOffLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 10))),
            "turning the delay off is the loosest single edit there is",
        )
    }

    @Test
    fun aLongerWaitTightens() {
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 15))),
        )
    }

    @Test
    fun aShorterWaitLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 5))),
        )
    }

    @Test
    fun theSameWaitIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 10))),
        )
    }

    @Test
    fun theWaitCanBeEditedWhileTheFeatureIsOff() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(
                SettingsChange.SetStrict(StrictConfig(enabled = false, delayMin = 30)),
                base.copy(strict = StrictConfig(enabled = false, delayMin = 10)),
            ),
            "nothing waits while the feature is off, so configuring the wait must apply at once",
        )
    }

    // ── Flags ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun turningCheckInsOffLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetFlags(reflection = false, gymHint = null, webGuard = null)),
        )
    }

    @Test
    fun turningCheckInsOnTightens() {
        val off = base.copy(reflectionEnabled = false)
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetFlags(reflection = true, gymHint = null, webGuard = null), off),
        )
    }

    @Test
    fun turningWebGuardOffLoosens() {
        assertEquals(
            ChangeClass.LOOSENING,
            classify(SettingsChange.SetFlags(reflection = null, gymHint = null, webGuard = false)),
        )
    }

    @Test
    fun turningWebGuardOnTightens() {
        val off = base.copy(webGuardEnabled = false)
        assertEquals(
            ChangeClass.TIGHTENING,
            classify(SettingsChange.SetFlags(reflection = null, gymHint = null, webGuard = true), off),
        )
    }

    @Test
    fun theGymHintIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetFlags(reflection = null, gymHint = false, webGuard = null)),
        )
    }

    @Test
    fun anUnchangedFlagIsNeutral() {
        assertEquals(
            ChangeClass.NEUTRAL,
            classify(SettingsChange.SetFlags(reflection = true, gymHint = true, webGuard = true)),
        )
    }

    @Test
    fun aMixedFlagEditWaitsBecauseOfItsLooseningHalf() {
        // Check-ins come back on (tightening) while web guard goes off (loosening). The loosening
        // half must not ride in on the tightening one.
        val mixed = base.copy(reflectionEnabled = false)
        assertEquals(
            ChangeClass.LOOSENING,
            classify(
                SettingsChange.SetFlags(reflection = true, gymHint = null, webGuard = false),
                mixed,
            ),
        )
    }

    // ── When the edit lands ────────────────────────────────────────────────────────────────────

    @Test
    fun tighteningAndNeutralEditsLandAtOnce() {
        val now = 1_793_288_400_000L
        assertEquals(now, policy.applyAtWall(now, ChangeClass.TIGHTENING, StrictConfig(enabled = true, delayMin = 10), false))
        assertEquals(now, policy.applyAtWall(now, ChangeClass.NEUTRAL, StrictConfig(enabled = true, delayMin = 10), false))
    }

    @Test
    fun aLooseningEditWaitsTheConfiguredMinutes() {
        val now = 1_793_288_400_000L
        assertEquals(
            now + 10 * 60_000L,
            policy.applyAtWall(now, ChangeClass.LOOSENING, StrictConfig(enabled = true, delayMin = 10), false),
        )
        assertEquals(
            now + 15 * 60_000L,
            policy.applyAtWall(now, ChangeClass.LOOSENING, StrictConfig(enabled = true, delayMin = 15), false),
        )
    }

    @Test
    fun ramadanWaitsThirtyMinutes() {
        val now = 1_793_288_400_000L
        assertEquals(
            now + DefaultStrictPolicy.RAMADAN_DELAY_MIN * 60_000L,
            policy.applyAtWall(now, ChangeClass.LOOSENING, StrictConfig(enabled = true, delayMin = 10), true),
        )
        assertEquals(
            now + 30 * 60_000L,
            policy.applyAtWall(now, ChangeClass.LOOSENING, StrictConfig(enabled = true, delayMin = 0), true),
            "Ramadan's own wait wins over a zero delay",
        )
    }

    @Test
    fun aZeroOrNegativeDelayLandsAtOnce() {
        val now = 1_793_288_400_000L
        assertEquals(now, policy.applyAtWall(now, ChangeClass.LOOSENING, StrictConfig(enabled = true, delayMin = 0), false))
        assertEquals(now, policy.applyAtWall(now, ChangeClass.LOOSENING, StrictConfig(enabled = true, delayMin = -5), false))
    }
}
