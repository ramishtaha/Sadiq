package com.ramish.sadiq.intercept

import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.ui.intercept.OverrideWaitState
import com.ramish.sadiq.ui.intercept.formatOverrideCountdown
import com.ramish.sadiq.ui.intercept.overrideCostLineRes
import com.ramish.sadiq.ui.intercept.overrideRemainingMs
import com.ramish.sadiq.ui.intercept.overrideWaitState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §A.6 is a closed table: the door may say these sentences and no others.
 *
 * The test is a plain JVM test for the same reason [ForbiddenWordsTest] is one — it reads
 * `strings_intercept.xml` as text, so the copy is pinned even though there is no Robolectric on the
 * test classpath and no `Context` to resolve `R.string` against. Each state is asserted twice: once
 * as the resource the screen picks ([overrideCostLineRes], the wait state, the captions) and once as
 * the sentence that resource holds, character for character.
 *
 * `InterceptCopy`'s own methods are the one-line `getString` resolution of exactly those ids and of
 * the fixed labels asserted by name below, so nothing user-visible can change here unnoticed.
 */
class InterceptCopyTest {

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** Every `<string>` in strings_intercept.xml, by resource name, with Android's escapes undone. */
    private val strings: Map<String, String> = readStrings()

    private fun readStrings(): Map<String, String> {
        val file = File("src/main/res/values/strings_intercept.xml")
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        return STRING_TAG.findAll(file.readText())
            .associate { match ->
                val name = match.groupValues[1]
                val value = match.groupValues[2]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                name to value
            }
    }

    /** The text behind a resource name, failing loudly when the name is not shipped at all. */
    private fun text(name: String): String =
        strings[name] ?: throw AssertionError("strings_intercept.xml has no <string name=\"$name\">")

    private fun ui(
        kind: SurfaceKind = SurfaceKind.COOLDOWN,
        overrideLeft: Int? = null,
        overrideWaitMs: Long? = null,
        overrideArmedElapsed: Long? = null,
        overrideStarsAtStake: Int? = null,
        overrideBackAtWall: Long? = null,
        timeUpKind: GrantKind? = null,
    ) = InterceptUi(
        kind = kind,
        packageName = "com.instagram.android",
        domain = null,
        suggestion = null,
        stayOptionsMin = emptyList(),
        stayRequiresHold = false,
        resumeRemainingMs = null,
        freeScrollOptionsMin = emptyList(),
        freeScrollAvailableMs = null,
        quickNeedsLeft = null,
        budgetRemainingMs = null,
        restRemainingMs = null,
        restEndsWall = null,
        focusRemainingMs = null,
        prayer = null,
        isJumuah = false,
        nextFajrWall = null,
        windowEndWall = null,
        reflectionOpens = null,
        reflectReason = null,
        timeUpKind = timeUpKind,
        leaveEarnsStar = false,
        forceDark = false,
        testMode = false,
        mode = Mode.BALANCED,
        overrideLeft = overrideLeft,
        overrideWaitMs = overrideWaitMs,
        overrideArmedElapsed = overrideArmedElapsed,
        overrideStarsAtStake = overrideStarsAtStake,
        overrideBackAtWall = overrideBackAtWall
    )

    /** The armed confirm view, at the day's or the night's wait. */
    private fun armedUi(
        kind: SurfaceKind = SurfaceKind.COOLDOWN,
        starsAtStake: Int? = null,
        waitMs: Long = EngineRules.OVERRIDE_WAIT_MS,
    ) = ui(
        kind = kind,
        overrideLeft = 1,
        overrideWaitMs = waitMs,
        overrideArmedElapsed = 1_000L,
        overrideStarsAtStake = starsAtStake
    )

    // ── the entry ────────────────────────────────────────────────────────────────────────────

    @Test
    fun entry_label_is_open_anyway() {
        assertEquals("Open anyway", text("intercept_override_entry"))
    }

    @Test
    fun entry_talkback_names_the_app_the_cost_and_the_wait() {
        assertEquals(
            "Open {Instagram} anyway. No star, and it asks you to wait first.",
            text("intercept_override_entry_description").replace("%1\$s", "{Instagram}")
        )
        // The label is what a sighted user reads; the description must not repeat it as a sentence.
        assertFalse(text("intercept_override_entry_description").contains("%2\$s"))
    }

    @Test
    fun entry_caption_names_what_is_left_of_the_two() {
        assertEquals("Open anyway · %1\$d left today", text("intercept_override_caption_day"))
    }

    @Test
    fun bedtime_caption_is_one_a_night() {
        assertEquals("Open anyway · one a night", text("intercept_override_caption_night"))
        assertEquals(
            "intercept_override_caption_night",
            captionName(ui(kind = SurfaceKind.SLEEP_GATE, overrideLeft = 1))
        )
    }

    @Test
    fun day_caption_is_the_counted_one() {
        assertEquals(
            "intercept_override_caption_day",
            captionName(ui(kind = SurfaceKind.COOLDOWN, overrideLeft = 2))
        )
        assertEquals(
            "intercept_override_caption_day",
            captionName(ui(kind = SurfaceKind.TIME_UP, overrideLeft = 1, timeUpKind = GrantKind.OVERRIDE))
        )
    }

    @Test
    fun caption_is_absent_when_there_is_no_count_to_name() {
        // overrideLeft null means the engine is not offering the door: no caption, never "0 left".
        assertNull(captionName(ui(kind = SurfaceKind.COOLDOWN, overrideLeft = null)))
    }

    // ── the confirm view's headline and cost ─────────────────────────────────────────────────

    @Test
    fun headline_names_the_app_and_nothing_else() {
        assertEquals(
            "Open {Instagram} anyway?",
            text("intercept_override_headline").replace("%1\$s", "{Instagram}")
        )
    }

    @Test
    fun cost_line_default_is_the_one_with_no_stars_in_it() {
        assertEquals(
            "You can. It's 10 minutes, and it won't light a star.",
            text("intercept_override_cost_default")
        )
        // The default line names no number, so it takes no argument.
        assertFalse(text("intercept_override_cost_default").contains("%"))
        assertEquals(
            "intercept_override_cost_default",
            lineName(armedUi(kind = SurfaceKind.COOLDOWN, starsAtStake = null))
        )
    }

    @Test
    fun cost_line_at_bedtime_names_tonights_three_quiet_night_stars() {
        val night = armedUi(kind = SurfaceKind.SLEEP_GATE, starsAtStake = 3, waitMs = EngineRules.OVERRIDE_WAIT_NIGHT_MS)
        assertEquals("intercept_override_cost_night", lineName(night))
        assertEquals(
            "You can. It's 10 minutes, and tonight won't light its 3 quiet-night stars.",
            text("intercept_override_cost_night").replace("%1\$d", night.overrideStarsAtStake.toString())
        )
    }

    @Test
    fun cost_line_in_the_morning_window_names_its_two_stars() {
        // A rest running in the morning window is a COOLDOWN surface, not MORNING: the line follows
        // the stars at stake, not the kind.
        val morning = armedUi(kind = SurfaceKind.COOLDOWN, starsAtStake = 2)
        assertEquals("intercept_override_cost_morning", lineName(morning))
        assertEquals(
            "You can. It's 10 minutes, and this morning won't light its 2 stars.",
            text("intercept_override_cost_morning").replace("%1\$d", morning.overrideStarsAtStake.toString())
        )
    }

    @Test
    fun cost_line_is_plain_once_the_window_stars_are_already_gone() {
        // A grant, a quick minute or a reconciled use already spent the window: there is nothing
        // left to lose, so the line must not claim three stars.
        val bedtimeAfterAQuickMinute = armedUi(
            kind = SurfaceKind.SLEEP_GATE,
            starsAtStake = null,
            waitMs = EngineRules.OVERRIDE_WAIT_NIGHT_MS
        )
        assertEquals("intercept_override_cost_default", lineName(bedtimeAfterAQuickMinute))
    }

    // ── the hold's three labels ─────────────────────────────────────────────────────────────

    @Test
    fun waiting_label_is_a_clock() {
        assertEquals("Ready in %1\$s", text("intercept_override_ready_in"))
        assertEquals(
            "Ready in 0:24",
            text("intercept_override_ready_in").replace("%1\$s", formatOverrideCountdown(24_000L))
        )
    }

    @Test
    fun waited_without_a_reason_asks_for_one() {
        assertEquals("Pick one above first", text("intercept_override_pick_reason"))
        assertEquals(
            OverrideWaitState.PICK_REASON,
            overrideWaitState(remainingMs = 0L, reasonPicked = false)
        )
    }

    @Test
    fun ready_label_names_the_hold_and_the_length() {
        assertEquals("Hold to open · 10 min", text("intercept_override_hold"))
        assertEquals(OverrideWaitState.READY, overrideWaitState(remainingMs = 0L, reasonPicked = true))
        assertEquals(OverrideWaitState.WAITING, overrideWaitState(remainingMs = 1L, reasonPicked = true))
    }

    @Test
    fun hold_talkback_names_the_app_and_the_length() {
        assertEquals(
            "Open {Instagram} for 10 minutes.",
            text("intercept_override_hold_description").replace("%1\$s", "{Instagram}")
        )
    }

    // ── the reasons ─────────────────────────────────────────────────────────────────────────

    @Test
    fun reason_header_asks_the_one_question() {
        assertEquals("What's pulling you there?", text("intercept_override_reason_header"))
    }

    @Test
    fun reason_chips_are_the_six_plus_i_need_it_last() {
        assertEquals(
            listOf("Bored", "Tired", "Avoiding something", "Lonely", "Anxious", "Just habit"),
            ReflectionReason.CHECK_IN.map { text(chipName(it)) }
        )
        assertEquals("I need it for something", text(chipName(ReflectionReason.NEED)))
        // The confirm view draws `ReflectionReason.entries`, which is the six and then NEED.
        assertEquals(
            listOf(
                "Bored",
                "Tired",
                "Avoiding something",
                "Lonely",
                "Anxious",
                "Just habit",
                "I need it for something"
            ),
            ReflectionReason.entries.map { text(chipName(it)) }
        )
    }

    @Test
    fun the_need_reason_is_never_a_check_in_chip() {
        assertFalse(ReflectionReason.CHECK_IN.contains(ReflectionReason.NEED))
    }

    // ── the caps ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun back_at_line_states_the_time_it_comes_back() {
        assertEquals("Open anyway is back at %1\$s.", text("intercept_override_back_at"))
    }

    @Test
    fun time_up_after_the_door_states_the_ten_minutes() {
        assertEquals("That's the 10 minutes.", text("intercept_time_up_override_headline"))
    }

    // ── the wait, as arithmetic ──────────────────────────────────────────────────────────────

    @Test
    fun remaining_is_measured_from_the_engines_arm_time() {
        val armed = armedUi(waitMs = EngineRules.OVERRIDE_WAIT_MS)
        assertEquals(
            EngineRules.OVERRIDE_WAIT_MS,
            overrideRemainingMs(armed, nowElapsedMs = armed.overrideArmedElapsed!!)
        )
        // 15 s after the arm: an activity recreated here draws 0:15, not 0:30.
        assertEquals(
            15_000L,
            overrideRemainingMs(armed, nowElapsedMs = armed.overrideArmedElapsed!! + 15_000L)
        )
        // Past the wait: zero, never a negative clock.
        assertEquals(
            0L,
            overrideRemainingMs(armed, nowElapsedMs = armed.overrideArmedElapsed!! + 90_000L)
        )
    }

    @Test
    fun remaining_is_zero_when_the_door_is_not_armed() {
        assertEquals(0L, overrideRemainingMs(ui(kind = SurfaceKind.COOLDOWN, overrideLeft = 2), 5_000L))
    }

    @Test
    fun the_night_wait_is_the_engines_sixty_seconds() {
        val night = armedUi(
            kind = SurfaceKind.SLEEP_GATE,
            waitMs = EngineRules.OVERRIDE_WAIT_NIGHT_MS
        )
        assertEquals(
            60_000L,
            overrideRemainingMs(night, nowElapsedMs = night.overrideArmedElapsed!!)
        )
    }

    @Test
    fun countdown_is_a_minute_and_seconds_and_never_goes_negative() {
        assertEquals("0:30", formatOverrideCountdown(30_000L))
        assertEquals("1:00", formatOverrideCountdown(60_000L))
        assertEquals("0:24", formatOverrideCountdown(23_001L))
        // Rounded up: 0:00 belongs to the end of the wait and to nothing before it.
        assertEquals("0:01", formatOverrideCountdown(1L))
        assertEquals("0:00", formatOverrideCountdown(0L))
        assertEquals("0:00", formatOverrideCountdown(-5_000L))
    }

    // ── the engine's facts behind the copy ──────────────────────────────────────────────────

    @Test
    fun every_line_names_the_engines_own_ten_minutes() {
        assertEquals(10L * EngineRules.MINUTE_MS, EngineRules.OVERRIDE_MS)
        val tenMinuteLines = listOf(
            "intercept_override_cost_default",
            "intercept_override_cost_night",
            "intercept_override_cost_morning",
            "intercept_override_hold",
            "intercept_override_hold_description",
            "intercept_time_up_override_headline"
        )
        for (name in tenMinuteLines) {
            assertTrue(text(name).contains("10 min"), "$name must name the 10 minutes the engine grants")
        }
    }

    @Test
    fun the_waits_are_the_engines_thirty_and_sixty_seconds() {
        assertEquals(30_000L, EngineRules.OVERRIDE_WAIT_MS)
        assertEquals(60_000L, EngineRules.OVERRIDE_WAIT_NIGHT_MS)
    }

    @Test
    fun no_door_line_uses_an_emoji() {
        // HyperOS draws its own emoji set, and a sad face from Sadiq reads as shame (§A.5).
        val doorStrings = strings.filterKeys { it.startsWith("intercept_override_") }
        assertTrue(doorStrings.isNotEmpty())
        for ((name, value) in doorStrings) {
            assertFalse(
                value.any { it.code > 0x2100 },
                "$name must be plain text, found: $value"
            )
        }
    }

    // ── helpers: the resource name each state picks ─────────────────────────────────────────

    /** The resource name of the cost line the confirm view would draw for [ui]. */
    private fun lineName(ui: InterceptUi): String =
        when (overrideCostLineRes(ui)) {
            com.ramish.sadiq.R.string.intercept_override_cost_default -> "intercept_override_cost_default"
            com.ramish.sadiq.R.string.intercept_override_cost_night -> "intercept_override_cost_night"
            com.ramish.sadiq.R.string.intercept_override_cost_morning -> "intercept_override_cost_morning"
            else -> throw AssertionError("unknown cost line")
        }

    /**
     * The resource name of the caption, mirroring [InterceptCopy.overrideCaption]: Bedtime states
     * the night rule, the day states the count, and no count means no caption at all.
     */
    private fun captionName(ui: InterceptUi): String? = when {
        ui.kind == SurfaceKind.SLEEP_GATE -> "intercept_override_caption_night"
        ui.overrideLeft != null -> "intercept_override_caption_day"
        else -> null
    }

    private fun chipName(reason: ReflectionReason): String = when (reason) {
        ReflectionReason.BORED -> "intercept_reflect_chip_bored"
        ReflectionReason.TIRED -> "intercept_reflect_chip_tired"
        ReflectionReason.AVOIDING -> "intercept_reflect_chip_avoiding"
        ReflectionReason.LONELY -> "intercept_reflect_chip_lonely"
        ReflectionReason.ANXIOUS -> "intercept_reflect_chip_anxious"
        ReflectionReason.HABIT -> "intercept_reflect_chip_habit"
        ReflectionReason.NEED -> "intercept_reflect_chip_need"
    }

    private companion object {
        val STRING_TAG = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
