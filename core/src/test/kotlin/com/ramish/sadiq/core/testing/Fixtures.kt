package com.ramish.sadiq.core.testing

import com.ramish.sadiq.core.economy.EconomyDay
import com.ramish.sadiq.core.economy.EconomyRules
import com.ramish.sadiq.core.economy.LeaveKind
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.Budget
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.EngineState
import com.ramish.sadiq.core.engine.FocusState
import com.ramish.sadiq.core.engine.Grant
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.InterceptUi
import com.ramish.sadiq.core.engine.Kindle
import com.ramish.sadiq.core.engine.LedgerEntry
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.Phase
import com.ramish.sadiq.core.engine.Prompt
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.Rest
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.engine.TestArm
import com.ramish.sadiq.core.engine.UsageSpan
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionAction
import com.ramish.sadiq.core.insight.SuggestionContext
import com.ramish.sadiq.core.insight.SuggestionKey
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.ActiveWindow
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardContext
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.PrayerDay
import com.ramish.sadiq.core.model.PrayerName
import com.ramish.sadiq.core.model.ProtectedSpan
import com.ramish.sadiq.core.model.RamadanConfig
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.model.StrictConfig
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.time.TimeSnapshot
import java.time.LocalDate

/**
 * The shared test vocabulary: one builder per contract value, with the defaults an ordinary evening
 * would have. Tests override only the fields they are about, so a fixture never hides the thing under
 * test behind six irrelevant arguments.
 *
 * Times default to the evening of 29 October 2026 in Thane, the night the plan's worked examples use.
 */
object Fixtures {
    /** The local date the default fixtures sit on. */
    val EPOCH_DAY: Int = LocalDate.of(2026, 10, 29).toEpochDay().toInt()

    /** The guarded app most examples use. */
    val INSTAGRAM: GuardedApp = GuardedApp("com.instagram.android", "Instagram")

    /** A second guarded app, for tests that change the app mid-grant. */
    val YOUTUBE: GuardedApp = GuardedApp("com.google.android.youtube", "YouTube")

    /** A default evening snapshot: 21:10, before the sleep gate starts. */
    val EVENING: TimeSnapshot = TestTime.at("2026-10-29T21:10")

    private const val MINUTE_MS: Long = 60_000L

    private fun wall(localIso: String): Long = TestTime.at(localIso).wallMs

    // ── settings ────────────────────────────────────────────────────────────────────────────────

    /**
     * A config with one guarded app and every default intact.
     *
     * @param mode the mode; Balanced by default.
     * @param apps the guarded apps.
     * @param domains guarded web domains.
     * @param reflectionEnabled whether check-ins may appear.
     * @param webGuardEnabled whether domains are enforced.
     * @param sleepGate bedtime gate settings.
     * @param morning morning window settings.
     * @param strict delay settings.
     * @param prayer prayer inputs.
     */
    fun config(
        mode: Mode = Mode.BALANCED,
        apps: List<GuardedApp> = listOf(INSTAGRAM),
        domains: Set<String> = emptySet(),
        reflectionEnabled: Boolean = true,
        webGuardEnabled: Boolean = false,
        sleepGate: SleepGateConfig = SleepGateConfig(),
        morning: MorningConfig = MorningConfig(),
        strict: StrictConfig = StrictConfig(),
        prayer: PrayerConfig = PrayerConfig()
    ): GuardConfig = GuardConfig(
        mode = mode,
        apps = apps,
        domains = domains,
        prayer = prayer,
        sleepGate = sleepGate,
        morning = morning,
        ramadan = RamadanConfig(),
        strict = strict,
        reflectionEnabled = reflectionEnabled,
        gymHintEnabled = true,
        webGuardEnabled = webGuardEnabled
    )

    // ── schedule ───────────────────────────────────────────────────────────────────────────────

    /** Thane prayer times for the default date. */
    fun prayerDay(
        epochDay: Int = EPOCH_DAY,
        fajr: Long = wall("2026-10-29T05:21"),
        sunrise: Long = wall("2026-10-29T06:36"),
        dhuhr: Long = wall("2026-10-29T12:21"),
        asr: Long = wall("2026-10-29T15:36"),
        maghrib: Long = wall("2026-10-29T18:06"),
        isha: Long = wall("2026-10-29T19:21"),
        nextFajr: Long = wall("2026-10-30T05:22"),
        isFriday: Boolean = false
    ): PrayerDay = PrayerDay(
        epochDay = epochDay,
        fajr = fajr,
        sunrise = sunrise,
        dhuhr = dhuhr,
        asr = asr,
        maghrib = maghrib,
        isha = isha,
        nextFajr = nextFajr,
        isFriday = isFriday
    )

    /** No window is active. */
    fun noWindow(): ActiveWindow = ActiveWindow(kind = WindowKind.NONE, startWall = null, endWall = null)

    /**
     * An active window.
     *
     * @param kind which window.
     * @param startWall start in wall milliseconds.
     * @param endWall end in wall milliseconds.
     * @param prayer the owning prayer, for a prayer window.
     * @param isJumuah whether it is the Friday window.
     */
    fun window(
        kind: WindowKind,
        startWall: Long? = wall("2026-10-29T18:06"),
        endWall: Long? = wall("2026-10-29T18:26"),
        prayer: PrayerName? = null,
        isJumuah: Boolean = false
    ): ActiveWindow = ActiveWindow(
        kind = kind,
        startWall = startWall,
        endWall = endWall,
        prayer = prayer,
        isJumuah = isJumuah
    )

    /** A Maghrib prayer window around 18:10. */
    fun maghribWindow(now: Long = wall("2026-10-29T18:10")): ActiveWindow = ActiveWindow(
        kind = WindowKind.PRAYER,
        startWall = now - 4 * MINUTE_MS,
        endWall = now + 16 * MINUTE_MS,
        prayer = PrayerName.MAGHRIB,
        isJumuah = false
    )

    /**
     * A protected span.
     *
     * @param kind sleep gate or morning.
     * @param startWall start in wall milliseconds.
     * @param endWall end in wall milliseconds.
     */
    fun span(
        kind: WindowKind = WindowKind.SLEEP_GATE,
        startWall: Long = wall("2026-10-28T22:30"),
        endWall: Long = wall("2026-10-29T05:21")
    ): ProtectedSpan = ProtectedSpan(kind = kind, startWall = startWall, endWall = endWall)

    /**
     * A context at an ordinary instant, with no window active and nothing just ended.
     *
     * @param now the clocks.
     * @param config the settings.
     * @param window the active window.
     * @param logicalDay the logical day.
     * @param today prayer times.
     * @param nextPrayer the nearest upcoming prayer.
     * @param nextPrayerWall its adhan; Isha's by default.
     * @param ramadanActive whether Ramadan is in force.
     * @param minuteOfDay minutes since midnight; derived from [now] by default.
     * @param endedProtected protected windows that just ended.
     */
    fun context(
        now: TimeSnapshot = EVENING,
        config: GuardConfig = config(),
        window: ActiveWindow = noWindow(),
        logicalDay: Int = EPOCH_DAY,
        today: PrayerDay = prayerDay(),
        nextPrayer: PrayerName? = PrayerName.ISHA,
        nextPrayerWall: Long? = today.isha,
        ramadanActive: Boolean = false,
        minuteOfDay: Int = TestTime.minuteOfDay(now),
        endedProtected: List<ProtectedSpan> = emptyList()
    ): GuardContext = GuardContext(
        config = config,
        window = window,
        logicalDay = logicalDay,
        today = today,
        nextPrayer = nextPrayer,
        nextPrayerWall = nextPrayerWall,
        ramadanActive = ramadanActive,
        minuteOfDay = minuteOfDay,
        endedProtected = endedProtected
    )

    // ── engine state ───────────────────────────────────────────────────────────────────────────

    /** A full budget for the given mode. */
    fun budget(
        remainingMs: Long = Mode.BALANCED.budgetMin * MINUTE_MS,
        lastGuardedUseElapsed: Long? = null
    ): Budget = Budget(remainingMs = remainingMs, lastGuardedUseElapsed = lastGuardedUseElapsed)

    /** A rest with the given time left. */
    fun rest(remainingMs: Long = 5 * MINUTE_MS, lastAccountedElapsed: Long = 0L): Rest =
        Rest(remainingMs = remainingMs, lastAccountedElapsed = lastAccountedElapsed)

    /**
     * A grant.
     *
     * @param id engine id.
     * @param kind what the time is for.
     * @param grantedMs the length chosen.
     * @param consumedMs how much is used.
     * @param startedWall when it began.
     * @param lastAccountedElapsed the accounting mark.
     * @param heldSinceElapsed when it was held, if it is held.
     * @param pausedSinceElapsed when it was saved, if it is paused.
     * @param windowAtStart the window when it began.
     * @param firstPackage the first app it covered.
     */
    fun grant(
        id: String = "g1",
        kind: GrantKind = GrantKind.STAY,
        grantedMs: Long = 5 * MINUTE_MS,
        consumedMs: Long = 0L,
        startedWall: Long = EVENING.wallMs,
        lastAccountedElapsed: Long = EVENING.elapsedMs,
        heldSinceElapsed: Long? = null,
        pausedSinceElapsed: Long? = null,
        windowAtStart: WindowKind = WindowKind.NONE,
        firstPackage: String = INSTAGRAM.packageName
    ): Grant = Grant(
        id = id,
        kind = kind,
        grantedMs = grantedMs,
        consumedMs = consumedMs,
        startedWall = startedWall,
        lastAccountedElapsed = lastAccountedElapsed,
        heldSinceElapsed = heldSinceElapsed,
        pausedSinceElapsed = pausedSinceElapsed,
        windowAtStart = windowAtStart,
        firstPackage = firstPackage
    )

    /** A focus session. */
    fun focus(
        id: String = "f1",
        plannedMs: Long = 25 * MINUTE_MS,
        elapsedMs: Long = 0L,
        lastAccountedElapsed: Long = EVENING.elapsedMs,
        startedWall: Long = EVENING.wallMs,
        sound: SoundKind = SoundKind.NONE
    ): FocusState = FocusState(
        id = id,
        plannedMs = plannedMs,
        elapsedMs = elapsedMs,
        lastAccountedElapsed = lastAccountedElapsed,
        startedWall = startedWall,
        sound = sound
    )

    /** A pending kindle. */
    fun kindle(
        id: String = "k1",
        leave: LeaveKind = LeaveKind.LEFT_PROMPT,
        createdElapsed: Long = EVENING.elapsedMs,
        confirmAtElapsed: Long = EVENING.elapsedMs + EconomyRules.KINDLE_CONFIRM_MS,
        sourcePackage: String? = INSTAGRAM.packageName,
        window: WindowKind = WindowKind.NONE
    ): Kindle = Kindle(
        id = id,
        leave = leave,
        createdElapsed = createdElapsed,
        confirmAtElapsed = confirmAtElapsed,
        sourcePackage = sourcePackage,
        window = window
    )

    /** A ledger entry for a protected window. */
    fun ledgerEntry(
        kind: WindowKind = WindowKind.SLEEP_GATE,
        startWall: Long = wall("2026-10-28T22:30"),
        grants: Int = 0,
        quickNeeds: Int = 0,
        reconciledUsage: Boolean = false,
        overrides: Int = 0
    ): LedgerEntry = LedgerEntry(
        kind = kind,
        startWall = startWall,
        grants = grants,
        quickNeeds = quickNeeds,
        reconciledUsage = reconciledUsage,
        overrides = overrides
    )

    /** The prompt on screen. */
    fun prompt(
        kind: SurfaceKind = SurfaceKind.PAUSE,
        packageName: String = INSTAGRAM.packageName,
        domain: String? = null,
        shownWall: Long = EVENING.wallMs,
        reflectReason: ReflectionReason? = null,
        testMode: Boolean = false,
        returnRoute: String? = null,
        overrideArmedElapsed: Long? = null
    ): Prompt = Prompt(
        kind = kind,
        packageName = packageName,
        domain = domain,
        shownWall = shownWall,
        reflectReason = reflectReason,
        testMode = testMode,
        returnRoute = returnRoute,
        overrideArmedElapsed = overrideArmedElapsed
    )

    /** A self-test arm. */
    fun testArm(
        packageName: String = INSTAGRAM.packageName,
        returnRoute: String = "health",
        armedElapsed: Long = EVENING.elapsedMs
    ): TestArm = TestArm(
        packageName = packageName,
        returnRoute = returnRoute,
        armedElapsed = armedElapsed
    )

    /**
     * An idle state at the default evening instant.
     *
     * @param now the clocks the state was last touched at.
     * @param phase what the engine is doing.
     * @param foregroundPackage the app in front.
     * @param foregroundGuarded whether it is guarded.
     * @param screenOn whether the screen is on.
     * @param prompt the surface on screen.
     * @param grant the grant in force.
     * @param budget today's budget.
     * @param rest the rest in force.
     * @param focus the focus in force.
     * @param kindles pending kindles.
     * @param recentOpensWall recent guarded opens.
     * @param lastReflectWall when the last check-in was answered.
     * @param ledger what protected windows are owed.
     * @param logicalDay the logical day of the economy counters.
     * @param economy today's counters.
     * @param testArm a live self-test arm.
     * @param bootCount the boot the elapsed readings belong to.
     * @param seq the id counter.
     * @param lastSeenWall wall time of the previous input.
     * @param lastSeenElapsed elapsed time of the previous input.
     */
    fun state(
        now: TimeSnapshot = EVENING,
        phase: Phase = Phase.IDLE,
        foregroundPackage: String? = null,
        foregroundGuarded: Boolean = false,
        screenOn: Boolean = true,
        prompt: Prompt? = null,
        grant: Grant? = null,
        budget: Budget = budget(),
        rest: Rest? = null,
        focus: FocusState? = null,
        kindles: List<Kindle> = emptyList(),
        recentOpensWall: List<Long> = emptyList(),
        lastReflectWall: Long? = null,
        ledger: List<LedgerEntry> = emptyList(),
        logicalDay: Int = EPOCH_DAY,
        economy: EconomyDay = EconomyDay(logicalDay = logicalDay),
        testArm: TestArm? = null,
        bootCount: Int = now.bootCount,
        seq: Long = 0L,
        lastSeenWall: Long = now.wallMs,
        lastSeenElapsed: Long = now.elapsedMs
    ): EngineState = EngineState(
        schema = 1,
        phase = phase,
        foregroundPackage = foregroundPackage,
        foregroundGuarded = foregroundGuarded,
        screenOn = screenOn,
        prompt = prompt,
        grant = grant,
        budget = budget,
        rest = rest,
        focus = focus,
        kindles = kindles,
        recentOpensWall = recentOpensWall,
        lastReflectWall = lastReflectWall,
        ledger = ledger,
        economy = economy,
        testArm = testArm,
        bootCount = bootCount,
        seq = seq,
        lastSeenWall = lastSeenWall,
        lastSeenElapsed = lastSeenElapsed
    )

    // ── economy and sky ───────────────────────────────────────────────────────────────────────

    /** Today's star counters. */
    fun economyDay(
        logicalDay: Int = EPOCH_DAY,
        litLeave: Int = 0,
        litFocus: Int = 0,
        litTotal: Int = 0,
        lastLeaveLitWall: Long? = null,
        freeUsedMs: Long = 0L,
        quickNeedsUsed: Int = 0,
        overridesUsed: Int = 0
    ): EconomyDay = EconomyDay(
        logicalDay = logicalDay,
        litLeave = litLeave,
        litFocus = litFocus,
        litTotal = litTotal,
        lastLeaveLitWall = lastLeaveLitWall,
        freeUsedMs = freeUsedMs,
        quickNeedsUsed = quickNeedsUsed,
        overridesUsed = overridesUsed
    )

    /** A star. */
    fun star(
        kind: StarKind = StarKind.LEAVE,
        litWall: Long = EVENING.wallMs,
        logicalDay: Int = EPOCH_DAY,
        window: WindowKind = WindowKind.NONE,
        sourcePackage: String? = INSTAGRAM.packageName,
        sourceRef: String? = null
    ): StarAward = StarAward(
        kind = kind,
        litWall = litWall,
        logicalDay = logicalDay,
        window = window,
        sourcePackage = sourcePackage,
        sourceRef = sourceRef
    )

    /** A lit star as the sky sees it. */
    fun skyStar(
        index: Int = 0,
        kind: StarKind = StarKind.LEAVE,
        litWall: Long = EVENING.wallMs,
        logicalDay: Int = EPOCH_DAY,
        sourcePackage: String? = INSTAGRAM.packageName
    ): SkyStar = SkyStar(
        index = index,
        kind = kind,
        litWall = litWall,
        logicalDay = logicalDay,
        sourcePackage = sourcePackage
    )

    // ── surfaces and suggestions ───────────────────────────────────────────────────────────────

    /**
     * A surface with the ordinary options.
     *
     * @param kind which surface.
     * @param packageName the app underneath.
     * @param domain the matched domain.
     * @param suggestion the suggested next action.
     * @param stayOptionsMin the Stay chips.
     * @param stayRequiresHold whether the chip must be held.
     * @param resumeRemainingMs saved grant remaining.
     * @param freeScrollOptionsMin free-scroll chips.
     * @param freeScrollAvailableMs today's free-scroll balance.
     * @param quickNeedsLeft quick minutes left.
     * @param budgetRemainingMs budget left.
     * @param restRemainingMs rest left.
     * @param restEndsWall when the rest ends.
     * @param focusRemainingMs focus left.
     * @param prayer the owning prayer.
     * @param isJumuah whether it is Jumu'ah.
     * @param nextFajrWall tomorrow's Fajr.
     * @param windowEndWall when the window ends.
     * @param reflectionOpens guarded opens in this window.
     * @param reflectReason the check-in answer already given.
     * @param timeUpKind the grant kind that expired.
     * @param leaveEarnsStar whether leaving lights a star.
     * @param forceDark whether the surface must be dark.
     * @param testMode whether it is a self-test.
     * @param mode the mode in force.
     * @param overrideLeft "Open anyway" uses left today, null when not offered.
     * @param overrideWaitMs the wait before the hold.
     * @param overrideArmedElapsed when the door was armed, null unless the confirm view shows.
     * @param overrideStarsAtStake window stars at stake, 3 or 2, or null.
     * @param overrideBackAtWall when the door comes back, null unless a cap is spent.
     */
    fun ui(
        kind: SurfaceKind = SurfaceKind.PAUSE,
        packageName: String = INSTAGRAM.packageName,
        domain: String? = null,
        suggestion: Suggestion? = null,
        stayOptionsMin: List<Int> = EngineRules.STAY_CHIPS_MIN,
        stayRequiresHold: Boolean = false,
        resumeRemainingMs: Long? = null,
        freeScrollOptionsMin: List<Int> = emptyList(),
        freeScrollAvailableMs: Long? = null,
        quickNeedsLeft: Int? = null,
        budgetRemainingMs: Long? = null,
        restRemainingMs: Long? = null,
        restEndsWall: Long? = null,
        focusRemainingMs: Long? = null,
        prayer: PrayerName? = null,
        isJumuah: Boolean = false,
        nextFajrWall: Long? = null,
        windowEndWall: Long? = null,
        reflectionOpens: Int? = null,
        reflectReason: ReflectionReason? = null,
        timeUpKind: GrantKind? = null,
        leaveEarnsStar: Boolean = true,
        forceDark: Boolean = false,
        testMode: Boolean = false,
        mode: Mode = Mode.BALANCED,
        overrideLeft: Int? = null,
        overrideWaitMs: Long? = null,
        overrideArmedElapsed: Long? = null,
        overrideStarsAtStake: Int? = null,
        overrideBackAtWall: Long? = null
    ): InterceptUi = InterceptUi(
        kind = kind,
        packageName = packageName,
        domain = domain,
        suggestion = suggestion,
        stayOptionsMin = stayOptionsMin,
        stayRequiresHold = stayRequiresHold,
        resumeRemainingMs = resumeRemainingMs,
        freeScrollOptionsMin = freeScrollOptionsMin,
        freeScrollAvailableMs = freeScrollAvailableMs,
        quickNeedsLeft = quickNeedsLeft,
        budgetRemainingMs = budgetRemainingMs,
        restRemainingMs = restRemainingMs,
        restEndsWall = restEndsWall,
        focusRemainingMs = focusRemainingMs,
        prayer = prayer,
        isJumuah = isJumuah,
        nextFajrWall = nextFajrWall,
        windowEndWall = windowEndWall,
        reflectionOpens = reflectionOpens,
        reflectReason = reflectReason,
        timeUpKind = timeUpKind,
        leaveEarnsStar = leaveEarnsStar,
        forceDark = forceDark,
        testMode = testMode,
        mode = mode,
        overrideLeft = overrideLeft,
        overrideWaitMs = overrideWaitMs,
        overrideArmedElapsed = overrideArmedElapsed,
        overrideStarsAtStake = overrideStarsAtStake,
        overrideBackAtWall = overrideBackAtWall
    )

    /** A suggestion. */
    fun suggestion(
        key: SuggestionKey = SuggestionKey.GENERIC_WATER,
        action: SuggestionAction = SuggestionAction.NONE,
        prayer: PrayerName? = null,
        minutes: Int? = null,
        atWall: Long? = null
    ): Suggestion = Suggestion(key = key, prayer = prayer, minutes = minutes, atWall = atWall, action = action)

    /** A suggester context at the default evening instant. */
    fun suggestionContext(
        window: ActiveWindow = noWindow(),
        nowWall: Long = EVENING.wallMs,
        minuteOfDay: Int = TestTime.minuteOfDay(EVENING),
        nextPrayer: PrayerName? = PrayerName.ISHA,
        nextPrayerWall: Long? = wall("2026-10-29T19:21"),
        maghribWall: Long = wall("2026-10-29T18:06"),
        nextFajrWall: Long = wall("2026-10-30T05:22"),
        reason: ReflectionReason? = null,
        gymHintEnabled: Boolean = true,
        openIndexToday: Int = 0,
        logicalDay: Int = EPOCH_DAY
    ): SuggestionContext = SuggestionContext(
        window = window,
        nowWall = nowWall,
        minuteOfDay = minuteOfDay,
        nextPrayer = nextPrayer,
        nextPrayerWall = nextPrayerWall,
        maghribWall = maghribWall,
        nextFajrWall = nextFajrWall,
        reason = reason,
        gymHintEnabled = gymHintEnabled,
        openIndexToday = openIndexToday,
        logicalDay = logicalDay
    )

    // ── history ──────────────────────────────────────────────────────────────────────────────

    /** A usage row. */
    fun usageRow(
        logicalDay: Int = EPOCH_DAY,
        packageName: String = INSTAGRAM.packageName,
        foregroundMs: Long = 30 * MINUTE_MS,
        opens: Int = 4,
        nightMs: Long = 0L
    ): UsageDayRow = UsageDayRow(
        logicalDay = logicalDay,
        packageName = packageName,
        foregroundMs = foregroundMs,
        opens = opens,
        nightMs = nightMs
    )

    /** A recorded open. */
    fun openEvent(
        at: Long = EVENING.wallMs,
        logicalDay: Int = EPOCH_DAY,
        packageName: String = INSTAGRAM.packageName,
        domain: String? = null,
        surface: SurfaceKind = SurfaceKind.PAUSE,
        outcome: OpenOutcome = OpenOutcome.LEFT,
        decisionMs: Long? = 4_000L,
        grantId: String? = null
    ): RecordEvent.Open = RecordEvent.Open(
        at = at,
        logicalDay = logicalDay,
        packageName = packageName,
        domain = domain,
        surface = surface,
        outcome = outcome,
        decisionMs = decisionMs,
        grantId = grantId
    )

    /** A recorded check-in. */
    fun reflected(
        at: Long = EVENING.wallMs,
        logicalDay: Int = EPOCH_DAY,
        reason: ReflectionReason = ReflectionReason.BORED,
        packageName: String = INSTAGRAM.packageName,
        opensInWindow: Int = 6
    ): RecordEvent.Reflected = RecordEvent.Reflected(
        at = at,
        logicalDay = logicalDay,
        reason = reason,
        packageName = packageName,
        opensInWindow = opensInWindow
    )

    /** A usage span for reconciliation. */
    fun usageSpan(
        packageName: String = INSTAGRAM.packageName,
        startWall: Long = EVENING.wallMs,
        endWall: Long = EVENING.wallMs + 5 * MINUTE_MS
    ): UsageSpan = UsageSpan(packageName = packageName, startWall = startWall, endWall = endWall)
}
