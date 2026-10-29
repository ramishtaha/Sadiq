package com.ramish.sadiq.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.contract.StarRepository
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.schedule.ScheduleEvaluator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SkyUiState(
    val loading: Boolean = true,
    val needsOnboarding: Boolean = false,
    val stars: List<SkyStar> = emptyList(),
    val kindling: Int = 0,
    val totalLit: Int = 0,
    val litToday: Int = 0,
    val freeMinutesLeft: Int = 0,
    val status: SkyStatus? = null,
    val card: HomeCardModel? = null,
    val asleep: Boolean = false,
    val skySeed: Long = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SkyViewModel @Inject constructor(
    private val starRepository: StarRepository,
    private val settingsRepository: SettingsRepository,
    private val guardController: GuardController,
    private val homeCardProviders: Set<@JvmSuppressWildcards HomeCardProvider>,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val homeAction: HomeAction,
    private val skyStatusResolver: SkyStatusResolver,
    private val clock: Clock,
    private val scheduleEvaluator: ScheduleEvaluator,
) : ViewModel() {

    private val _needsOnboarding = MutableStateFlow(false)
    private val _totalLitStars = MutableStateFlow(0)

    /**
     * The three inputs that are always available synchronously, folded into one value so the main
     * combine below stays inside `kotlinx.coroutines`' typed overloads (they stop at five flows).
     */
    private data class SkyInputs(
        val prefs: UiPrefs,
        val config: GuardConfig,
        val totalLit: Int
    )

    private val baseInputs: Flow<SkyInputs> = combine(
        settingsRepository.prefs,
        settingsRepository.config,
        _totalLitStars
    ) { prefs, config, totalLit -> SkyInputs(prefs, config, totalLit) }

    val uiState: StateFlow<SkyUiState> = combine(
        baseInputs,
        starRepository.observeSky(),
        guardController.state,
        permissionStatusProvider.changes,
        getHomeCardsFlow()
    ) { base, stars, engineState, permissions, homeCard ->
        val now = clock.now()
        val guardContext = scheduleEvaluator.contextAt(
            now = now,
            config = base.config,
            lastSeenWall = engineState.lastSeenWall
        )

        val skyStatus = skyStatusResolver.resolve(
            config = base.config,
            nowWall = now.wallMs,
            nextDeadlineWall = engineState.rest?.let { now.wallMs + it.remainingMs },
            windowKind = guardContext.window.kind,
            permissionSnapshot = permissions,
            focusRunning = engineState.focus != null,
            focusPlannedMs = engineState.focus?.plannedMs ?: 0L,
            focusStartedWall = engineState.focus?.startedWall ?: 0L,
            windowEndWall = guardContext.window.endWall,
            prayerName = guardContext.nextPrayer?.name
        )

        SkyUiState(
            loading = false,
            needsOnboarding = !base.prefs.onboardingDone || _needsOnboarding.value,
            stars = stars,
            kindling = engineState.kindles.size,
            totalLit = base.totalLit,
            litToday = engineState.economy.litTotal,
            freeMinutesLeft = (engineState.economy.freeUsedMs / 60_000).toInt(),
            status = skyStatus,
            card = homeCard,
            asleep = !permissions.accessibilityEnabled,
            skySeed = base.prefs.skySeed,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        SkyUiState(loading = true)
    )

    init {
        viewModelScope.launch {
            settingsRepository.prefs.collect {
                if (!it.onboardingDone) {
                    _needsOnboarding.value = true
                }
            }
        }
        viewModelScope.launch {
            starRepository.observeSky().collect {
                _totalLitStars.value = starRepository.countLit()
            }
        }
    }

    fun onStarTapped(index: Int) {
        // TODO: Implement star tooltip logic
    }

    fun onCardAction(id: String) {
        // TODO: Implement home card action logic
    }

    private fun getHomeCardsFlow(): Flow<HomeCardModel?> {
        val sortedProviders = homeCardProviders.sortedByDescending { it.priority }
        return combine(sortedProviders.map { it.card }) { cards ->
            cards.firstOrNull { it != null }
        }
    }
}
