package com.ramish.sadiq.core.contract

import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.engine.Prompt
import com.ramish.sadiq.core.engine.Rest
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.engine.TestArm
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.RamadanMode
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.model.SurfaceMode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.testing.Fixtures
import com.ramish.sadiq.core.time.TimeSnapshot
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persisted contract must survive a JSON round trip byte-for-byte in meaning: the engine snapshot
 * is the only thing between a HyperOS kill and a lost grant, and pending settings changes are stored
 * as JSON in `pending_changes`.
 */
class ContractSerializationTest {

    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    private val defaultsOmitted: Json = Json {
        encodeDefaults = false
        prettyPrint = false
    }

    @Test
    fun engineState_roundTripsThroughJson() {
        val state = Fixtures.state(
            phase = Phase.PROMPTING,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            prompt = Fixtures.prompt(
                kind = SurfaceKind.REFLECT,
                reflectReason = ReflectionReason.BORED
            ),
            grant = Fixtures.grant(heldSinceElapsed = 1_000L, pausedSinceElapsed = 2_000L),
            rest = Rest(remainingMs = 60_000L, lastAccountedElapsed = 5_000L),
            focus = Fixtures.focus(sound = SoundKind.RAIN, elapsedMs = 90_000L),
            kindles = listOf(Fixtures.kindle()),
            recentOpensWall = listOf(1L, 2L, 3L),
            lastReflectWall = 4L,
            ledger = listOf(Fixtures.ledgerEntry()),
            economy = Fixtures.economyDay(
                litLeave = 2,
                litFocus = 1,
                litTotal = 3,
                lastLeaveLitWall = 5L,
                freeUsedMs = 60_000L,
                quickNeedsUsed = 1
            ),
            testArm = TestArm("com.google.android.youtube", "health", 6L),
            bootCount = 7,
            seq = 8L,
            lastSeenWall = 9L,
            lastSeenElapsed = 10L
        )

        val text = json.encodeToString(EngineState.serializer(), state)
        val decoded = json.decodeFromString(EngineState.serializer(), text)

        assertEquals(state, decoded)
        assertTrue(text.contains("\"schema\":1"), "the snapshot keeps its schema field")
    }

    @Test
    fun engineState_decodesWhenOptionalFieldsAreOmitted() {
        val state = Fixtures.state()
        val text = defaultsOmitted.encodeToString(EngineState.serializer(), state)
        val decoded = defaultsOmitted.decodeFromString(EngineState.serializer(), text)

        assertEquals(state, decoded)
        assertEquals(1, decoded.schema, "schema is the one field that always has a default")
    }

    @Test
    fun guardConfig_roundTripsThroughJson() {
        val config = GuardConfig(
            mode = Mode.HARDCORE,
            apps = listOf(
                GuardedApp("com.instagram.android", "Instagram"),
                GuardedApp("com.google.android.youtube", "YouTube", SurfaceMode.SHORTS_ONLY)
            ),
            domains = setOf("instagram.com", "youtube.com"),
            prayer = PrayerConfig(
                lat = 24.8607,
                lon = 67.0011,
                place = "Karachi",
                method = CalcMethod.MUSLIM_WORLD_LEAGUE,
                madhab = Madhab.SHAFI,
                windowMin = 25,
                jumuahWindowMin = 45
            ),
            sleepGate = SleepGateConfig(enabled = false, startMinuteOfDay = 1320),
            morning = MorningConfig(enabled = false, endMinuteOfDay = 540),
            ramadan = RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = -1),
            strict = StrictConfig(enabled = true, delayMin = 30),
            reflectionEnabled = false,
            gymHintEnabled = false,
            webGuardEnabled = true
        )

        val text = json.encodeToString(GuardConfig.serializer(), config)
        val decoded = json.decodeFromString(GuardConfig.serializer(), text)

        assertEquals(config, decoded)
        assertEquals(config.apps, decoded.apps)
        assertEquals(config.domains, decoded.domains)
    }

    @Test
    fun guardConfig_fillsDefaultsWhenKeysAreMissing() {
        val text = defaultsOmitted.encodeToString(GuardConfig.serializer(), GuardConfig())
        val decoded = defaultsOmitted.decodeFromString(GuardConfig.serializer(), text)

        assertEquals(GuardConfig(), decoded)
        assertEquals(Mode.BALANCED, decoded.mode)
        assertEquals(19.2183, decoded.prayer.lat)
        assertEquals(72.9781, decoded.prayer.lon)
        assertEquals("Thane, Maharashtra", decoded.prayer.place)
        assertEquals(20, decoded.prayer.windowMin)
        assertEquals(60, decoded.prayer.jumuahWindowMin)
        assertEquals(1350, decoded.sleepGate.startMinuteOfDay)
        assertEquals(570, decoded.morning.endMinuteOfDay)
        assertEquals(10, decoded.strict.delayMin)
    }

    @Test
    fun settingsChange_roundTripsForEveryVariant() {
        val changes = listOf(
            SettingsChange.SetMode(Mode.HARDCORE),
            SettingsChange.SetApps(
                listOf(
                    GuardedApp("com.instagram.android", "Instagram"),
                    GuardedApp("com.google.android.youtube", "YouTube", SurfaceMode.SHORTS_ONLY)
                )
            ),
            SettingsChange.SetSurfaceMode("com.instagram.android", SurfaceMode.SHORTS_ONLY),
            SettingsChange.SetDomains(setOf("instagram.com", "youtube.com")),
            SettingsChange.SetSleepGate(SleepGateConfig(enabled = false, startMinuteOfDay = 1320)),
            SettingsChange.SetMorning(MorningConfig(enabled = false, endMinuteOfDay = 540)),
            SettingsChange.SetPrayer(
                PrayerConfig(
                    lat = 24.8607,
                    lon = 67.0011,
                    place = "Karachi",
                    method = CalcMethod.KARACHI,
                    madhab = Madhab.HANAFI,
                    windowMin = 20,
                    jumuahWindowMin = 60
                )
            ),
            SettingsChange.SetRamadan(RamadanConfig(mode = RamadanMode.ON, hijriOffsetDays = 2)),
            SettingsChange.SetStrict(StrictConfig(enabled = true, delayMin = 45)),
            SettingsChange.SetFlags(reflection = false, gymHint = null, webGuard = true)
        )

        changes.forEach { change ->
            val text = json.encodeToString(SettingsChange.serializer(), change)
            val decoded = json.decodeFromString(SettingsChange.serializer(), text)
            assertEquals(change, decoded, "round trip failed for ${change::class.simpleName}")
        }
    }

    @Test
    fun settingsChange_keepsItsWireNames() {
        val expectations = mapOf(
            SettingsChange.SetMode(Mode.GENTLE) to "set_mode",
            SettingsChange.SetApps(emptyList()) to "set_apps",
            SettingsChange.SetSurfaceMode("pkg", SurfaceMode.WHOLE_APP) to "set_surface_mode",
            SettingsChange.SetDomains(emptySet()) to "set_domains",
            SettingsChange.SetSleepGate(SleepGateConfig()) to "set_sleep_gate",
            SettingsChange.SetMorning(MorningConfig()) to "set_morning",
            SettingsChange.SetPrayer(PrayerConfig()) to "set_prayer",
            SettingsChange.SetRamadan(RamadanConfig()) to "set_ramadan",
            SettingsChange.SetStrict(StrictConfig()) to "set_strict",
            SettingsChange.SetFlags(reflection = null, gymHint = null, webGuard = null) to "set_flags"
        )

        expectations.forEach { (change, wireName) ->
            val text = json.encodeToString(SettingsChange.serializer(), change)
            assertTrue(
                text.contains("\"type\":\"$wireName\""),
                "a pending change written by an older build must still decode: $text"
            )
        }
    }

    /**
     * A4-12's storage half: the removal record lives in the same JSON column as the change itself,
     * so no schema change and no migration are involved. A row written before the field existed
     * still decodes — with no record, which is the honest reading of it.
     */
    @Test
    fun setApps_decodesARowWrittenBeforeTheRemovalRecordExisted() {
        val olderBuild =
            """{"type":"set_apps","apps":[{"packageName":"com.instagram.android","label":"Instagram"}]}"""

        val decoded = json.decodeFromString(SettingsChange.serializer(), olderBuild)

        assertEquals(
            SettingsChange.SetApps(listOf(GuardedApp("com.instagram.android", "Instagram"))),
            decoded,
        )
        assertNull((decoded as SettingsChange.SetApps).removed, "an older row records no removals")
    }

    @Test
    fun setApps_roundTripsTheRecordedRemovals() {
        val change = SettingsChange.SetApps(
            apps = listOf(GuardedApp("com.google.android.youtube", "YouTube")),
            removed = setOf("com.instagram.android"),
        )

        val text = json.encodeToString(SettingsChange.serializer(), change)

        assertTrue(text.contains("\"removed\":[\"com.instagram.android\"]"), text)
        assertEquals(change, json.decodeFromString(SettingsChange.serializer(), text))
    }

    @Test
    fun starAward_roundTripsThroughJson() {
        val award = Fixtures.star(
            kind = com.ramish.sadiq.core.economy.StarKind.KEPT_WORD,
            window = WindowKind.PRAYER,
            sourcePackage = Fixtures.INSTAGRAM.packageName,
            sourceRef = "g42"
        )

        val text = json.encodeToString(com.ramish.sadiq.core.economy.StarAward.serializer(), award)
        val decoded = json.decodeFromString(com.ramish.sadiq.core.economy.StarAward.serializer(), text)

        assertEquals(award, decoded)
    }

    @Test
    fun timeSnapshot_roundTripsThroughJson() {
        val snapshot = TimeSnapshot(
            wallMs = 1_790_000_000_000L,
            elapsedMs = 4_000_000L,
            bootCount = 12,
            zoneId = "Asia/Kolkata"
        )

        val text = json.encodeToString(TimeSnapshot.serializer(), snapshot)
        val decoded = json.decodeFromString(TimeSnapshot.serializer(), text)

        assertEquals(snapshot, decoded)
    }

    @Test
    fun grant_roundTripsThroughJson() {
        val grant: Grant = Fixtures.grant(
            kind = GrantKind.FREE,
            grantedMs = 600_000L,
            consumedMs = 120_000L,
            heldSinceElapsed = null,
            pausedSinceElapsed = 240_000L,
            windowAtStart = WindowKind.SLEEP_GATE
        )

        val text = json.encodeToString(Grant.serializer(), grant)
        val decoded = json.decodeFromString(Grant.serializer(), text)

        assertEquals(grant, decoded)
    }

    @Test
    fun promptAndKindle_roundTripThroughJson() {
        val prompt: Prompt = Fixtures.prompt(
            kind = SurfaceKind.TIME_UP,
            reflectReason = ReflectionReason.AVOIDING,
            testMode = true,
            returnRoute = "health"
        )
        val decodedPrompt = json.decodeFromString(
            Prompt.serializer(),
            json.encodeToString(Prompt.serializer(), prompt)
        )

        val kindle = Fixtures.kindle(
            leave = com.ramish.sadiq.core.economy.LeaveKind.SCREEN_OFF,
            window = WindowKind.MORNING
        )
        val decodedKindle = json.decodeFromString(
            com.ramish.sadiq.core.engine.Kindle.serializer(),
            json.encodeToString(com.ramish.sadiq.core.engine.Kindle.serializer(), kindle)
        )

        assertEquals(prompt, decodedPrompt)
        assertEquals(kindle, decodedKindle)
        assertEquals(EconomyRules.KINDLE_CONFIRM_MS, kindle.confirmAtElapsed - kindle.createdElapsed)
    }

    // ── OV-0: the "Open anyway" contract ─────────────────────────────────────────────────────────

    /**
     * The three fields the override contract adds to the snapshot — the grant kind, the arm reading
     * on the prompt and the ledger count — survive the round trip, and are written under the names
     * the engine and the history will read.
     */
    @Test
    fun engineState_withOverrideFields_roundTripsThroughJson() {
        val armedAt: Long = Fixtures.EVENING.elapsedMs + 12_000L
        val state = Fixtures.state(
            phase = Phase.PROMPTING,
            foregroundPackage = Fixtures.INSTAGRAM.packageName,
            foregroundGuarded = true,
            prompt = Fixtures.prompt(
                kind = SurfaceKind.SLEEP_GATE,
                overrideArmedElapsed = armedAt
            ),
            grant = Fixtures.grant(
                kind = GrantKind.OVERRIDE,
                grantedMs = EngineRules.OVERRIDE_MS,
                lastAccountedElapsed = Fixtures.EVENING.elapsedMs
            ),
            ledger = listOf(Fixtures.ledgerEntry(overrides = 1)),
            economy = Fixtures.economyDay(overridesUsed = 1)
        )

        val text = json.encodeToString(EngineState.serializer(), state)
        val decoded = json.decodeFromString(EngineState.serializer(), text)

        assertEquals(state, decoded)
        assertEquals(GrantKind.OVERRIDE, decoded.grant?.kind)
        assertEquals(EngineRules.OVERRIDE_MS, decoded.grant?.grantedMs)
        assertEquals(armedAt, decoded.prompt?.overrideArmedElapsed, "the arm reading survives a process death")
        assertEquals(1, decoded.ledger.single().overrides)
        assertEquals(1, decoded.economy.overridesUsed)
        assertTrue(text.contains("\"kind\":\"OVERRIDE\""), text)
        assertTrue(text.contains("\"overrideArmedElapsed\":$armedAt"), text)
        assertTrue(text.contains("\"overrides\":1"), text)
        assertTrue(text.contains("\"overridesUsed\":1"), text)
        assertEquals(1, decoded.schema, "the override contract does not bump the snapshot schema")
    }

    /**
     * A snapshot captured from `3be730d`, before any of the override fields existed. It must still
     * decode — with the new fields at their defaults — because a phone that updates overnight wakes
     * up with yesterday's document in DataStore.
     */
    @Test
    fun engineState_capturedBeforeTheOverrideContract_decodesWithDefaults() {
        val decoded = json.decodeFromString(EngineState.serializer(), SNAPSHOT_3BE730D)

        assertEquals(1, decoded.schema)
        assertEquals(Phase.PROMPTING, decoded.phase)
        assertEquals(SurfaceKind.COOLDOWN, decoded.prompt?.kind)
        assertEquals(ReflectionReason.BORED, decoded.prompt?.reflectReason)
        assertNull(decoded.prompt?.overrideArmedElapsed, "an older snapshot has nothing armed")
        assertEquals(GrantKind.QUICK, decoded.grant?.kind)
        assertEquals(1, decoded.ledger.size)
        assertEquals(0, decoded.ledger.single().overrides, "an older snapshot counts no overrides")
        assertEquals(1, decoded.ledger.single().quickNeeds)
        assertEquals(0, decoded.economy.overridesUsed, "an older snapshot has spent none of the day's cap")
        assertEquals(1, decoded.economy.quickNeedsUsed)
        assertEquals(Fixtures.EVENING.wallMs, decoded.lastSeenWall)
        assertEquals(4L, decoded.seq, "the rest of the document is read as it was written")

        // …and the document it writes next carries the new fields explicitly, at their defaults.
        val rewritten = json.encodeToString(EngineState.serializer(), decoded)
        assertTrue(rewritten.contains("\"overrideArmedElapsed\":null"), rewritten)
        assertTrue(rewritten.contains("\"overrides\":0"), rewritten)
        assertTrue(rewritten.contains("\"overridesUsed\":0"), rewritten)
        assertEquals(decoded, json.decodeFromString(EngineState.serializer(), rewritten))
    }

    /**
     * Every field the contract adds is inert by default, so every call site that predates "Open
     * anyway" keeps its meaning: no door, no wait, no arm, nothing spent.
     */
    @Test
    fun overrideContract_newFieldsDefaultToInert() {
        val prompt = Fixtures.prompt()
        val ledger = Fixtures.ledgerEntry()
        val day = Fixtures.economyDay()
        val ui = Fixtures.ui()

        assertNull(prompt.overrideArmedElapsed)
        assertEquals(0, ledger.overrides)
        assertEquals(0, day.overridesUsed)
        assertNull(ui.overrideLeft, "null means the door is not offered")
        assertNull(ui.overrideWaitMs)
        assertNull(ui.overrideArmedElapsed, "null means the confirm view is not showing")
        assertNull(ui.overrideStarsAtStake)
        assertNull(ui.overrideBackAtWall, "null means no cap is spent")
    }

    private companion object {
        /**
         * Written by the engine at commit `3be730d` — the snapshot `Fixtures.state(...)` with a
         * COOLDOWN prompt, a QUICK grant, a rest, one kindle and one sleep-gate ledger entry.
         * Verbatim; do not regenerate it from the current model or it stops being a migration test.
         */
        const val SNAPSHOT_3BE730D: String =
            """{"schema":1,"phase":"PROMPTING","foregroundPackage":"com.instagram.android","foregroundGuarded":true,"screenOn":true,"prompt":{"kind":"COOLDOWN","packageName":"com.instagram.android","domain":null,"shownWall":1793288400000,"reflectReason":"BORED","testMode":false,"returnRoute":null},"grant":{"id":"g1","kind":"QUICK","grantedMs":60000,"consumedMs":30000,"startedWall":1793288400000,"lastAccountedElapsed":26082630000,"heldSinceElapsed":null,"pausedSinceElapsed":null,"windowAtStart":"NONE","firstPackage":"com.instagram.android"},"budget":{"remainingMs":600000,"lastGuardedUseElapsed":null},"rest":{"remainingMs":1800000,"lastAccountedElapsed":26082600000},"focus":null,"kindles":[{"id":"k1","leave":"LEFT_PROMPT","createdElapsed":26082600000,"confirmAtElapsed":26082780000,"sourcePackage":"com.instagram.android","window":"NONE"}],"recentOpensWall":[1793288340000,1793288400000],"lastReflectWall":1793284800000,"ledger":[{"kind":"SLEEP_GATE","startWall":1793206800000,"grants":0,"quickNeeds":1,"reconciledUsage":false}],"economy":{"logicalDay":20755,"litLeave":1,"litFocus":0,"litTotal":1,"lastLeaveLitWall":1793281200000,"freeUsedMs":300000,"quickNeedsUsed":1},"testArm":null,"bootCount":1,"seq":4,"lastSeenWall":1793288400000,"lastSeenElapsed":26082600000}"""
    }
}
