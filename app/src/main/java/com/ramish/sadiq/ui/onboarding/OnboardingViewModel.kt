package com.ramish.sadiq.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.StarRepository
import com.ramish.sadiq.contract.Tri
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.GuardReducer
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.MorningConfig
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.model.SleepGateConfig
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.core.time.TimeSnapshot
import com.ramish.sadiq.guard.GuardControllerImpl
import com.ramish.sadiq.guard.SystemClockSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import com.ramish.sadiq.ui.onboarding.OnboardingPlan.StepId

/** The current state of the wizard. */
data class OnboardingUiState(
    val steps: List<OnboardingPlan.StepId>,
    val index: Int,
    val perms: PermissionSnapshot,
    val apps: List<AppInfo>,
    val selected: Set<String>,
    val mode: Mode,
    val sleepStartMin: Int,
    val morningEndMin: Int,
    val place: String,
    val testResult: SelfTestResult?,
    val loading: Boolean = false,
) {
    /** The current step. */
    val currentStep: OnboardingPlan.StepId? = steps.getOrNull(index)

    /** Whether the current step is the last one. */
    val isLast: Boolean = index == steps.lastIndex
}

/** Whether the self-test passed or failed. */
sealed interface SelfTestResult {
    data class Works(val packageName: String) : SelfTestResult
    data class Failed(val packageName: String) : SelfTestResult
}

/**
 * The wizard's own ViewModel: it drives the plan, tracks permission changes and saves the final
 * configuration to the repository.
 *
 * The wizard is one logical flow, so it is one ViewModel; each step is just a fragment of its UI.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permsProvider: PermissionStatusProvider,
    private val settings: SettingsRepository,
    private val oemSettingsLauncher: OemSettingsLauncher,
    private val installedAppsSource: InstalledAppsSource,
    private val guardController: GuardControllerImpl,
    private val starRepository: StarRepository,
    private val clock: SystemClockSource,
) : ViewModel() {

    private val mutableState: MutableStateFlow<OnboardingUiState> = MutableStateFlow(
        OnboardingUiState(
            steps = emptyList(),
            index = 0,
            perms = permsProvider.snapshot(),
            apps = emptyList(),
            selected = emptySet(),
            mode = Mode.BALANCED,
            sleepStartMin = GuardConfig().sleepGate.startMinuteOfDay,
            morningEndMin = GuardConfig().morning.endMinuteOfDay,
            place = GuardConfig().prayer.place,
            testResult = null,
            loading = true,
        )
    )

    /** The wizard's current state. */
    val uiState: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            // Load initial data for the app picker and settings
            val initialApps = installedAppsSource.launchableApps()
            val currentConfig = settings.config.first()
            val currentPrefs = settings.prefs.first()

            mutableState.update { current ->
                current.copy(
                    apps = AppCatalog.knownFeedsFirst(initialApps) { it.packageName },
                    selected = AppCatalog.knownFeeds.toSet(),
                    mode = currentConfig.mode,
                    sleepStartMin = currentConfig.sleepGate.startMinuteOfDay,
                    morningEndMin = currentConfig.morning.endMinuteOfDay,
                    place = currentConfig.prayer.place,
                    loading = false,
                    index = currentPrefs.onboardingStep, // Resume at the last saved step
                )
            }
            observePermissionsAndPlan()
            observeGuardControllerForSelfTest()
        }
    }

    /** Observes permission changes and updates the plan. */
    private fun observePermissionsAndPlan() {
        viewModelScope.launch {
            permsProvider.changes.collect { perms ->
                val prefs = settings.prefs.first()
                val newSteps = OnboardingPlan.steps(perms, perms.isXiaomiFamily)
                var nextIndex = mutableState.value.index
                // Skip steps that are now satisfied
                while (nextIndex < newSteps.size && isStepSatisfied(newSteps[nextIndex], perms, prefs)) {
                    nextIndex++
                }
                mutableState.update { current ->
                    current.copy(
                        perms = perms,
                        steps = newSteps,
                        index = nextIndex
                    )
                }
                saveProgress()
            }
        }
    }

    /** Observes GuardController for self-test results. */
    private fun observeGuardControllerForSelfTest() {
        viewModelScope.launch {
            guardController.effects.collect { effect ->
                val event = (effect as? com.ramish.sadiq.core.engine.Effect.Record)?.event
                if (event is RecordEvent.StarLit && event.award.kind == StarKind.WELCOME) {
                    // Self-test succeeded: the WELCOME star lit, so mark it as given.
                    settings.updatePrefs { it.copy(welcomeStarGiven = true) }
                    val testedPackage = guardController.state.value.testArm?.packageName
                    if (testedPackage != null) {
                        mutableState.update { it.copy(testResult = SelfTestResult.Works(testedPackage)) }
                    }
                }
            }
        }
        viewModelScope.launch {
            guardController.state.collect { engineState ->
                if (uiState.value.currentStep == StepId.TRY_IT && engineState.testArm == null && uiState.value.testResult == null) {
                    // Test arm disappeared without a star award, so it failed
                    uiState.value.apps.firstOrNull()?.let { app ->
                        mutableState.update { it.copy(testResult = SelfTestResult.Failed(app.packageName)) }
                    }
                }
            }
        }
    }

    /** Advances to the next step, skipping if already satisfied. */
    fun next() {
        viewModelScope.launch {
            val current = mutableState.value
            val newIndex = current.index + 1
            if (newIndex <= current.steps.lastIndex) {
                mutableState.update { it.copy(index = newIndex) }
                saveProgress()
            } else if (current.isLast) {
                // Final step, mark onboarding as done
                settings.updatePrefs { it.copy(onboardingDone = true) }
            }
        }
    }

    /** Returns to the previous step. */
    fun back() {
        val current = mutableState.value
        val newIndex = current.index - 1
        if (newIndex >= 0) {
            mutableState.update { it.copy(index = newIndex) }
            viewModelScope.launch { saveProgress() }
        }
    }

    /** Toggles the selected state of an app in the app picker. */
    fun toggleApp(packageName: String) {
        mutableState.update { current ->
            val newSelected = if (current.selected.contains(packageName)) {
                current.selected - packageName
            } else {
                current.selected + packageName
            }
            current.copy(selected = newSelected)
        }
    }

    /** Sets the guarding mode. */
    fun setMode(newMode: Mode) {
        mutableState.update { it.copy(mode = newMode) }
    }

    /** Sets the sleep gate start time. */
    fun setSleepStart(minuteOfDay: Int) {
        mutableState.update { it.copy(sleepStartMin = minuteOfDay) }
    }

    /** Sets the morning end time. */
    fun setMorningEnd(minuteOfDay: Int) {
        mutableState.update { it.copy(morningEndMin = minuteOfDay) }
    }

    /** Opens the settings target. */
    fun openFix(target: SettingsTarget) {
        oemSettingsLauncher.open(target)
    }

    /** Confirms a self-reported OEM step. */
    fun selfConfirm(stepId: OnboardingPlan.StepId) {
        viewModelScope.launch {
            settings.updatePrefs { prefs ->
                when (stepId) {
                    StepId.BATTERY_SAVER -> prefs.copy(batterySaverConfirmed = true)
                    StepId.AUTOSTART -> prefs.copy(autostartConfirmed = true)
                    StepId.RECENTS -> prefs.copy(recentsLockedConfirmed = true)
                    else -> prefs
                }
            }
        }
    }

    /** Runs the self-test on the first selected app. */
    fun runTry() {
        viewModelScope.launch {
            val app = uiState.value.apps.firstOrNull { uiState.value.selected.contains(it.packageName) }
            if (app != null) {
                guardController.submit(GuardInput.SelfTest(app.packageName, "onboarding"))
                // Launch the app here
                val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                } else {
                    // Fallback if app cannot be launched
                    android.util.Log.w(TAG, "Cannot launch app ${app.packageName} for self-test")
                    mutableState.update { it.copy(testResult = SelfTestResult.Failed(app.packageName)) }
                }
            }
        }
    }

    /** Checks if a step is satisfied and can be skipped. */
    private fun isStepSatisfied(step: OnboardingPlan.StepId, perms: PermissionSnapshot, prefs: UiPrefs): Boolean {
        return when (step) {
            OnboardingPlan.StepId.WELCOME -> false // Always shown
            OnboardingPlan.StepId.APPS -> false // User choice
            OnboardingPlan.StepId.MODE -> false // User choice
            OnboardingPlan.StepId.TIMES -> false // User choice
            OnboardingPlan.StepId.ACCESSIBILITY -> perms.accessibilityEnabled && perms.accessibilityConnected
            OnboardingPlan.StepId.RESTRICTED_SETTING -> false // Shown only if accessibility fails, never skipped
            OnboardingPlan.StepId.NOTIFICATIONS -> perms.notifications
            OnboardingPlan.StepId.POPUPS -> perms.backgroundPopups == Tri.YES
            OnboardingPlan.StepId.BATTERY_OPTIMIZATION -> perms.batteryUnrestricted
            OnboardingPlan.StepId.BATTERY_SAVER -> prefs.batterySaverConfirmed
            OnboardingPlan.StepId.AUTOSTART -> prefs.autostartConfirmed
            OnboardingPlan.StepId.RECENTS -> prefs.recentsLockedConfirmed
            OnboardingPlan.StepId.USAGE -> perms.usageAccess
            OnboardingPlan.StepId.TRY_IT -> prefs.welcomeStarGiven // Satisfied when the WELCOME star is given
        }
    }

    private suspend fun saveProgress() {
        settings.updatePrefs { it.copy(onboardingStep = mutableState.value.index) }
    }

    private companion object {
        const val TAG = "OnboardingViewModel"
    }
}
