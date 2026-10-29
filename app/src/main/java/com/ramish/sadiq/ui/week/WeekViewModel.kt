package com.ramish.sadiq.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.WeeklyInput
import com.ramish.sadiq.core.insight.WeeklyReview
import com.ramish.sadiq.core.insight.WeeklyReviewBuilder
import com.ramish.sadiq.core.insight.WeeklySuggestion
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.review.UsageDayAggregator
import com.ramish.sadiq.review.WeekCoverage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One bar of "WHERE IT WENT": an app, its week, and how much of the biggest bar it fills.
 *
 * @property packageName the app.
 * @property label the launcher label, or the package name when the app is gone.
 * @property foregroundMs guarded foreground time this week.
 * @property fraction width of the bar, relative to the largest app of the week, 0…1.
 */
data class WeekBar(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val fraction: Float,
)

/**
 * Everything §4.3 S4 draws.
 *
 * @property loading true until the first read finishes; the page stays blank while it is.
 * @property available false until Sadiq's records span a whole week; the screen then says so instead
 *   of drawing a half-week as if it were a week.
 * @property approximate true when the numbers come from Sadiq's own records rather than usage stats.
 * @property deltaMs this week minus last week, or null when there is no comparison week.
 * @property suggestionApplied true once the one suggestion has been applied from this screen, so it
 *   is not offered a second time in the same session.
 * @property suggestionAppliedAtWall when the applied change takes effect, if "Think it over" delayed
 *   it; null means it is already in force.
 */
data class WeekUiState(
    val loading: Boolean = true,
    val available: Boolean = false,
    val approximate: Boolean = false,
    val totalMs: Long = 0L,
    val deltaMs: Long? = null,
    val topApps: List<WeekBar> = emptyList(),
    val peakStartHour: Int? = null,
    val stars: Int = 0,
    val leaves: Int = 0,
    val quietNights: Int = 0,
    val focusMinutes: Int = 0,
    val topReason: ReflectionReason? = null,
    val suggestion: WeeklySuggestion? = null,
    val suggestionAppLabel: String? = null,
    val suggestionApplied: Boolean = false,
    val suggestionAppliedAtWall: Long? = null,
)

/**
 * The Week tab: where the time went, what he chose, what he said, and one change worth trying.
 *
 * The maths is not here. [WeeklyReviewBuilder] is the one implementation of §4.3 S4, and this class
 * only supplies it with a week (`HistoryRepository.weekInput`), resolves labels for the bars, and
 * applies the single suggestion through [SettingsRepository] — the same path every other screen uses.
 *
 * The usage rows are refreshed on open ([UsageDayAggregator]) because the platform's totals change
 * while Sadiq is not running. Without usage access the aggregator writes nothing and the builder is
 * handed Sadiq's own records, which it marks approximate; the screen says "Approximate" rather than
 * presenting a partial figure as measured.
 */
@HiltViewModel
class WeekViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val installedAppsSource: InstalledAppsSource,
    private val usageHistory: UsageHistorySource,
    private val usageDayAggregator: UsageDayAggregator,
    private val weeklyReviewBuilder: WeeklyReviewBuilder,
    private val oemSettingsLauncher: OemSettingsLauncher,
    private val clock: Clock,
) : ViewModel() {

    private val mutableState = MutableStateFlow(WeekUiState())

    /** The screen's whole state. */
    val uiState: StateFlow<WeekUiState> = mutableState.asStateFlow()

    /** Set when the suggestion was applied here, so it survives a reload without being re-offered. */
    private var suggestionApplied: Boolean = false
    private var appliedAtWall: Long? = null

    /**
     * Read the week. Called when the tab resumes: the numbers move while the app is in the
     * background, and a review that only loads once would be the previous week's by Sunday.
     */
    fun refresh() {
        viewModelScope.launch { load(refreshUsage = true) }
    }

    /**
     * Apply the one suggestion.
     *
     * Every branch goes through [SettingsRepository.apply], which is the one mutation path: a
     * tightening applies at once, and "Think it over" can still delay a loosening if it ever
     * classified one here. The screen then says when it took effect rather than assuming it did.
     */
    fun onApplySuggestion() {
        val suggestion: WeeklySuggestion = mutableState.value.suggestion ?: return
        if (suggestionApplied) return

        viewModelScope.launch {
            val config = settingsRepository.config.value
            val change: SettingsChange = when (suggestion) {
                is WeeklySuggestion.MoveSleepGate -> SettingsChange.SetSleepGate(
                    config.sleepGate.copy(startMinuteOfDay = suggestion.toMinuteOfDay)
                )

                is WeeklySuggestion.SwitchMode -> SettingsChange.SetMode(suggestion.to)

                is WeeklySuggestion.GuardApp -> SettingsChange.SetApps(
                    config.apps + GuardedApp(
                        packageName = suggestion.packageName,
                        label = mutableState.value.suggestionAppLabel ?: suggestion.packageName,
                    )
                )
            }

            val result: ApplyResult = runCatching { settingsRepository.apply(change) }
                .getOrNull()
                ?: return@launch

            suggestionApplied = true
            appliedAtWall = (result as? ApplyResult.Scheduled)?.applyAtWall
            load(refreshUsage = false)
        }
    }

    /** Open the OS page that grants usage access, for the "Approximate" chip's one action. */
    fun onTurnOnUsageAccess() {
        oemSettingsLauncher.open(SettingsTarget.USAGE_ACCESS)
    }

    /**
     * Build the review and publish it.
     *
     * A failed read leaves the screen blank rather than showing zeros: "0 m in guarded apps" would be
     * a claim, and a database that could not be read has not claimed anything.
     *
     * @param refreshUsage true when the platform's usage rows should be re-read first.
     */
    private suspend fun load(refreshUsage: Boolean) {
        if (refreshUsage) {
            runCatching { usageDayAggregator.aggregate() }
        }

        val now = clock.now()
        val today: Int = LogicalDays.of(now.wallMs, now.zoneId)
        val config = settingsRepository.config.value

        val stored: WeeklyInput = runCatching {
            historyRepository.weekInput(
                WeekCoverage.reviewDays(today),
                WeekCoverage.comparisonDays(today),
                config,
            )
        }.getOrNull() ?: run {
            mutableState.value = WeekUiState(loading = false)
            return
        }

        val input: WeeklyInput = withHourlyProfile(stored, config)
        val review: WeeklyReview = weeklyReviewBuilder.build(input)
        val labels: Map<String, String> = appLabels()

        val largestMs: Long = review.topApps.maxOfOrNull { (_, ms) -> ms } ?: 0L
        val bars: List<WeekBar> = review.topApps.map { (packageName, ms) ->
            WeekBar(
                packageName = packageName,
                label = labels[packageName] ?: packageName,
                foregroundMs = ms,
                fraction = if (largestMs <= 0L) 0f else (ms.toFloat() / largestMs).coerceIn(0.02f, 1f),
            )
        }

        val suggestion: WeeklySuggestion? = review.suggestion
        mutableState.value = WeekUiState(
            loading = false,
            available = WeekCoverage.isWeekReady(input),
            approximate = review.approximate,
            totalMs = review.totalMs,
            deltaMs = review.deltaMs,
            topApps = bars,
            peakStartHour = review.peakStartHour,
            stars = review.stars,
            leaves = review.leaves,
            quietNights = review.quietNights,
            focusMinutes = review.focusMinutes,
            topReason = review.topReason,
            suggestion = if (suggestionApplied) null else suggestion,
            suggestionAppLabel = (suggestion as? WeeklySuggestion.GuardApp)?.let { guard ->
                labels[guard.packageName] ?: guard.packageName
            },
            suggestionApplied = suggestionApplied,
            suggestionAppliedAtWall = appliedAtWall,
        )
    }

    /**
     * Swap in the platform's hour profile when it can be read.
     *
     * `HistoryRepository` builds `hourlyMs` from Sadiq's own grants: an honest fallback, but it only
     * knows the minutes Sadiq itself timed. `UsageHistorySource.hourlyMs` exists for exactly this line
     * (§5.1), so when usage access is granted the peak band is drawn from measured foreground time
     * instead. Everything else in the input stays exactly as the repository produced it.
     */
    private suspend fun withHourlyProfile(input: WeeklyInput, config: GuardConfig): WeeklyInput {
        if (!usageHistory.hasAccess()) return input
        val packages: Set<String> = config.apps.mapTo(HashSet()) { app -> app.packageName }
        if (packages.isEmpty()) return input

        val hours: LongArray = runCatching {
            usageHistory.hourlyMs(input.days, clock.now().zoneId, packages)
        }.getOrNull() ?: return input

        if (hours.size != HOURS_PER_DAY || hours.all { ms -> ms <= 0L }) return input
        return input.copy(hourlyMs = hours)
    }

    /**
     * Package name to launcher label for the week's apps.
     *
     * A package that is no longer installed keeps its package name: naming the app is nicer, but
     * guessing a label would be a small invention on a screen whose whole point is being exact.
     */
    private suspend fun appLabels(): Map<String, String> = runCatching {
        installedAppsSource.launchableApps().associate { app -> app.packageName to app.label }
    }.getOrDefault(emptyMap())

    private companion object {
        /** A day of hours; the builder reads the profile as hour-of-day slots. */
        const val HOURS_PER_DAY: Int = 24
    }
}
