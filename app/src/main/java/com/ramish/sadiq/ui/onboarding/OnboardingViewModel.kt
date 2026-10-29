package com.ramish.sadiq.ui.onboarding

import android.content.Context
import android.content.Intent
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
import com.ramish.sadiq.core.engine.Effect
import com.ramish.sadiq.core.engine.EngineRules
import com.ramish.sadiq.core.engine.GuardInput
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.guard.GuardControllerImpl
import com.ramish.sadiq.guard.SystemClockSource
import com.ramish.sadiq.ui.onboarding.OnboardingPlan.StepId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The current state of the wizard. */
data class OnboardingUiState(
    val steps: List<StepId>,
    val index: Int,
    val perms: PermissionSnapshot,
    val apps: List<AppInfo>,
    val selected: Set<String>,
    val mode: Mode,
    val sleepStartMin: Int,
    val morningEndMin: Int,
    val place: String,
    val testResult: SelfTestResult?,
    val testing: Boolean = false,
    val loading: Boolean = false,
    val done: Boolean = false,
) {
    /** The current step. */
    val currentStep: StepId? = steps.getOrNull(index)

    /** Whether the current step is the last one. */
    val isLast: Boolean = index == steps.lastIndex
}

/** Whether the self-test passed or failed. */
sealed interface SelfTestResult {
    data class Works(val packageName: String) : SelfTestResult
    data class Failed(val packageName: String) : SelfTestResult
}

/**
 * The wizard's own ViewModel: it drives the plan, tracks permission changes, saves every choice and
 * finishes the wizard.
 *
 * The wizard is one logical flow, so it is one ViewModel; each step is just a fragment of its UI.
 *
 * **Saving.** Every choice is written the moment the user leaves the step that made it: apps, mode,
 * Bedtime and the morning end all go through [SettingsRepository.apply], because those are guard
 * settings and `apply` is their only write path. The self-reported OEM steps go through
 * [SettingsRepository.updatePrefs]. Nothing waits for the last step, so a wizard killed at TRY_IT
 * still leaves a fully guarded phone.
 *
 * **Advancing.** The plan is a function of the permissions and the preferences, so the wizard
 * recomputes it on both. [refresh] is called from the screen's `ON_RESUME`, which is what carries a
 * step forward after the user grants something in the OS: `PermissionStatusProvider` re-reads, the
 * plan drops the satisfied step, and the wizard lands on the next unfinished one.
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

    /** Steps the user chose to skip for this run of the wizard. Never persisted, never a claim. */
    private val skipped: MutableSet<StepId> = mutableSetOf()

    /** Whether the user has already been sent to the accessibility page once (enables O5b). */
    private var accessibilityTried: Boolean = false

    /** Whether the user has already dismissed the restricted-setting fix, so it stops being offered. */
    private var restrictedDismissed: Boolean = false

    /** The last preferences read, so a Back tap can skip over a step it would immediately skip. */
    private var lastPrefs: UiPrefs? = null

    /** The package the self-test was armed for, or null when nothing is armed. */
    private var testedPackage: String? = null

    /** Whether a TEST surface has actually been seen since the last arm. */
    private var testSurfaceSeen: Boolean = false

    init {
        viewModelScope.launch {
            val installed = installedAppsSource.launchableApps()
            val config = settings.config.first()
            val prefs = settings.prefs.first()

            val installedPackages = installed.mapTo(mutableSetOf()) { it.packageName }
            val initialSelection = if (config.apps.isNotEmpty()) {
                // A wizard that runs again must not throw away a list the user curated elsewhere.
                config.apps.mapTo(mutableSetOf()) { it.packageName }.apply { retainAll(installedPackages) }
            } else {
                // Pre-select only the known feeds that are actually installed (G-18).
                AppCatalog.knownFeeds.filterTo(mutableSetOf()) { it in installedPackages }
            }

            val perms = permsProvider.snapshot()
            lastPrefs = prefs
            val steps = buildSteps(perms, prefs)
            val index = resumeIndex(prefs.onboardingStep, steps, prefs)

            mutableState.update { current ->
                current.copy(
                    apps = AppCatalog.knownFeedsFirst(installed) { it.packageName },
                    selected = initialSelection,
                    mode = config.mode,
                    sleepStartMin = config.sleepGate.startMinuteOfDay,
                    morningEndMin = config.morning.endMinuteOfDay,
                    place = config.prayer.place,
                    perms = perms,
                    steps = steps,
                    index = index.coerceIn(0, steps.size),
                    // A wizard resumed after the star was lit has already passed its own test.
                    testResult = if (prefs.welcomeStarGiven) {
                        initialSelection.firstOrNull()?.let { SelfTestResult.Works(it) }
                    } else {
                        null
                    },
                    loading = false,
                )
            }

            observePlan()
            observeSelfTest()
        }
    }

    // ── the plan ──────────────────────────────────────────────────────────────────────────────

    /**
     * Re-reads every permission and rebuilds the plan.
     *
     * Called from the screen's `ON_RESUME`: the user comes back from an OS page that Sadiq cannot be
     * notified about, and this is the only moment the wizard learns what they did there.
     */
    fun refresh() {
        permsProvider.refresh()
        viewModelScope.launch { reconcilePlan(advanceFromAccessibility = true) }
    }

    /** Observes permissions and preferences: either one can satisfy a step or add one. */
    private fun observePlan() {
        viewModelScope.launch {
            combine(permsProvider.changes, settings.prefs) { perms, prefs -> perms to prefs }
                .collect { (perms, prefs) ->
                    lastPrefs = prefs
                    reconcilePlan(advanceFromAccessibility = false, perms = perms, prefs = prefs)
                }
        }
    }

    /**
     * Folds the current permissions and preferences into the plan and moves the wizard to the right
     * step.
     *
     * @param advanceFromAccessibility true when this runs because the user came back from the
     *   accessibility page: if they have been there and the service is still off, the restricted
     *   setting is the likely cause and O5b is offered.
     * @param perms a reading to use instead of re-reading the provider.
     * @param prefs preferences to use instead of reading the store.
     */
    private suspend fun reconcilePlan(
        advanceFromAccessibility: Boolean,
        perms: PermissionSnapshot? = null,
        prefs: UiPrefs? = null,
    ) {
        if (mutableState.value.done || mutableState.value.loading) return
        val snapshot = perms ?: permsProvider.snapshot()
        val preferences = prefs ?: settings.prefs.first()
        lastPrefs = preferences
        val steps = buildSteps(snapshot, preferences)

        val state = mutableState.value
        var index = reconcileIndex(state, steps, snapshot, preferences)
        if (advanceFromAccessibility && !restrictedDismissed && state.currentStep == StepId.ACCESSIBILITY) {
            val restricted = steps.indexOf(StepId.RESTRICTED_SETTING)
            if (restricted >= 0 && !accessibilitySatisfied(snapshot)) index = restricted
        }

        if (index >= steps.size || steps.isEmpty()) {
            // Every step is satisfied. There is nothing left to show: finish rather than strand the
            // wizard on a step that no longer exists.
            finishInternal()
        } else {
            mutableState.update { it.copy(steps = steps, index = index, perms = snapshot) }
            if (index != state.index) saveProgress(index)
        }
    }

    /** The plan for one reading and one set of preferences, minus the steps the user skipped. */
    private fun buildSteps(perms: PermissionSnapshot, prefs: UiPrefs): List<StepId> =
        OnboardingPlan.steps(perms, perms.isXiaomiFamily, accessibilityTried)
            .filterNot { it in skipped }

    /**
     * Where the wizard should sit after the plan changed.
     *
     * If the current step still exists it keeps its place, unless it has just been satisfied. If it
     * has been removed (because the user granted it in the OS), the wizard moves to the first step
     * that follows it in the plan, so granting a permission advances the flow instead of stalling it.
     */
    private fun reconcileIndex(
        state: OnboardingUiState,
        steps: List<StepId>,
        perms: PermissionSnapshot,
        prefs: UiPrefs,
    ): Int {
        if (steps.isEmpty()) return 0
        val current = state.currentStep
        val base = if (current == null) {
            0
        } else {
            val existing = steps.indexOf(current)
            if (existing >= 0) {
                existing
            } else {
                steps.indexOfFirst { it.ordinal > current.ordinal }.takeIf { it >= 0 } ?: steps.size
            }
        }
        return firstUnfinished(base, steps, perms, prefs)
    }

    /** The first index at or after [from] whose step still needs the user. */
    private fun firstUnfinished(
        from: Int,
        steps: List<StepId>,
        perms: PermissionSnapshot,
        prefs: UiPrefs,
    ): Int {
        var index = from.coerceAtLeast(0)
        while (index < steps.size && isSatisfied(steps[index], perms, prefs)) index++
        return index
    }

    /**
     * Whether a step can be passed over.
     *
     * Only permission steps and the self-reported OEM steps can: the four choice steps and TRY_IT
     * always need the user, because Sadiq cannot guess an app list, a mode or a bedtime, and it will
     * not claim a star it did not see lit.
     */
    private fun isSatisfied(step: StepId, perms: PermissionSnapshot, prefs: UiPrefs): Boolean = when (step) {
        StepId.WELCOME, StepId.APPS, StepId.MODE, StepId.TIMES, StepId.RESTRICTED_SETTING, StepId.TRY_IT -> false
        StepId.ACCESSIBILITY -> accessibilitySatisfied(perms)
        StepId.NOTIFICATIONS -> perms.notifications
        StepId.POPUPS -> perms.backgroundPopups == Tri.YES
        StepId.BATTERY_OPTIMIZATION -> perms.batteryUnrestricted
        StepId.BATTERY_SAVER -> prefs.batterySaverConfirmed
        StepId.AUTOSTART -> prefs.autostartConfirmed
        StepId.RECENTS -> prefs.recentsLockedConfirmed
        StepId.USAGE -> perms.usageAccess
    }

    private fun accessibilitySatisfied(perms: PermissionSnapshot): Boolean =
        perms.accessibilityEnabled && perms.accessibilityConnected

    /** The step the wizard opens on, honouring the saved index without ever skipping a choice step. */
    private fun resumeIndex(saved: Int, steps: List<StepId>, prefs: UiPrefs): Int {
        if (steps.isEmpty()) return 0
        val start = if (saved in CHOICE_STEPS.indices) {
            saved
        } else {
            // Past the four choice steps: resume at the first permission step, then skip forward.
            steps.indexOfFirst { it !in CHOICE_STEPS }.takeIf { it >= 0 } ?: steps.lastIndex
        }
        return firstUnfinished(start, steps, permsProvider.snapshot(), prefs)
    }

    // ── navigation ────────────────────────────────────────────────────────────────────────────

    /** Saves the current step's choice and advances to the next unfinished step. */
    fun next() {
        viewModelScope.launch {
            val state = mutableState.value
            val step = state.currentStep ?: return@launch
            persist(step, state)
            advancePast(step)
        }
    }

    /**
     * Moves the wizard to the first unfinished step after [step].
     *
     * The successor is computed from the step being left, never from the current index: a preference
     * write re-emits through [observePlan] and may already have moved the wizard on, and advancing
     * twice would skip a step the user has not seen.
     *
     * @param step the step that was just finished.
     */
    private suspend fun advancePast(step: StepId) {
        val prefs = settings.prefs.first()
        val perms = permsProvider.snapshot()
        val steps = buildSteps(perms, prefs)
        val from = steps.indexOfFirst { it.ordinal > step.ordinal }.takeIf { it >= 0 } ?: steps.size
        val index = firstUnfinished(from, steps, perms, prefs)
        if (index >= steps.size || steps.isEmpty()) {
            finishInternal()
        } else {
            mutableState.update { it.copy(steps = steps, index = index, perms = perms) }
            saveProgress(index)
        }
    }

    /** Returns to the previous step the user can actually act on. */
    fun back() {
        val state = mutableState.value
        val prefs = lastPrefs
        val perms = state.perms
        var index = state.index - 1
        while (index > 0 && prefs != null && isSatisfied(state.steps[index], perms, prefs)) index--
        if (index >= 0 && index < state.steps.size) {
            if (state.currentStep == StepId.RESTRICTED_SETTING) restrictedDismissed = true
            mutableState.update { it.copy(index = index) }
            viewModelScope.launch { saveProgress(index) }
        }
    }

    /** Skips the current step for this run of the wizard. */
    fun skipCurrent() {
        val step = mutableState.value.currentStep ?: return
        skipped += step
        if (step == StepId.RESTRICTED_SETTING) restrictedDismissed = true
        viewModelScope.launch { reconcilePlan(advanceFromAccessibility = false) }
    }

    /** Confirms a self-reported OEM step and moves on: the two always happen together. */
    fun confirmStep(stepId: StepId) {
        viewModelScope.launch {
            settings.updatePrefs { prefs ->
                when (stepId) {
                    StepId.BATTERY_SAVER -> prefs.copy(batterySaverConfirmed = true)
                    StepId.AUTOSTART -> prefs.copy(autostartConfirmed = true)
                    StepId.RECENTS -> prefs.copy(recentsLockedConfirmed = true)
                    else -> prefs
                }
            }
            advancePast(stepId)
        }
    }

    /** Writes the choices, marks onboarding done and lets the screen leave the wizard. */
    fun finish() {
        viewModelScope.launch { finishInternal() }
    }

    /**
     * Ends the wizard: the choices are written, `onboardingDone` is set and the screen is told to
     * leave.
     *
     * `done` is set before the writes, not after: the preferences write re-emits through
     * [observePlan], and a plan recomputed in that window must see a finished wizard rather than
     * walk it back to the first step.
     */
    private suspend fun finishInternal() {
        if (mutableState.value.done) return
        val state = mutableState.value
        mutableState.update { it.copy(done = true, testing = false) }
        // The screen leaves as soon as `done` flips, which clears this ViewModel's scope. Without
        // NonCancellable the last writes are cancelled mid-flight and the wizard restarts at step 1
        // on the next launch — the user finishes setup and finds it undone.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            persist(StepId.APPS, state)
            persist(StepId.MODE, state)
            persist(StepId.TIMES, state)
            settings.updatePrefs { it.copy(onboardingDone = true, onboardingStep = 0) }
        }
    }

    // ── choices ───────────────────────────────────────────────────────────────────────────────

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

    /** Opens the settings target, remembering that accessibility has been attempted. */
    fun openFix(target: SettingsTarget) {
        if (target == SettingsTarget.ACCESSIBILITY) accessibilityTried = true
        oemSettingsLauncher.open(target)
    }

    /**
     * Writes whatever choice the step holds.
     *
     * @param step the step being left.
     * @param state the values to write.
     */
    private suspend fun persist(step: StepId, state: OnboardingUiState) {
        when (step) {
            StepId.APPS -> {
                val apps = state.apps
                    .filter { it.packageName in state.selected }
                    .map { GuardedApp(packageName = it.packageName, label = it.label) }
                settings.apply(SettingsChange.SetApps(apps))
            }

            StepId.MODE -> settings.apply(SettingsChange.SetMode(state.mode))

            StepId.TIMES -> {
                val config = settings.config.first()
                settings.apply(
                    SettingsChange.SetSleepGate(
                        config.sleepGate.copy(startMinuteOfDay = state.sleepStartMin)
                    )
                )
                settings.apply(
                    SettingsChange.SetMorning(config.morning.copy(endMinuteOfDay = state.morningEndMin))
                )
            }

            else -> Unit
        }
    }

    private suspend fun saveProgress(index: Int) {
        settings.updatePrefs { it.copy(onboardingStep = index) }
    }

    // ── the self-test ─────────────────────────────────────────────────────────────────────────

    /** Arms the self-test on the first selected app and opens it. */
    fun runTry() {
        viewModelScope.launch {
            val state = mutableState.value
            val app = state.apps.firstOrNull { state.selected.contains(it.packageName) } ?: return@launch
            // The guarded list must be in force before the test arms, or the surface the user sees
            // would not be the one their own choice produces.
            persist(StepId.APPS, state)
            testSurfaceSeen = false
            testedPackage = app.packageName
            mutableState.update { it.copy(testResult = null, testing = true) }

            guardController.submit(GuardInput.SelfTest(app.packageName, RETURN_ROUTE))
            if (!launchPackage(app.packageName)) {
                mutableState.update {
                    it.copy(testResult = SelfTestResult.Failed(app.packageName), testing = false)
                }
                return@launch
            }

            // T33a's arm lasts one minute. If no TEST surface appeared in that time, the test really
            // did not reach the user and the step says so. A surface that did appear is never a
            // failure: it is the question working, waiting for an answer.
            delay(EngineRules.SELF_TEST_ARM_MS + ARM_GRACE_MS)
            if (mutableState.value.testResult == null) {
                mutableState.update {
                    if (testSurfaceSeen) {
                        it.copy(testing = false)
                    } else {
                        it.copy(testResult = SelfTestResult.Failed(app.packageName), testing = false)
                    }
                }
            }
        }
    }

    /**
     * Watches the engine for the two effects that mean the question reached the user: the TEST
     * surface being left (which routes back here) and the history row that leave writes.
     *
     * The WELCOME star is recorded here rather than by the engine: E9 belongs to onboarding, and T33b
     * deliberately makes no economy call, so nothing else can light it.
     */
    private fun observeSelfTest() {
        viewModelScope.launch {
            guardController.effects.collect { effect ->
                when (effect) {
                    is Effect.OpenSadiq -> if (effect.route == RETURN_ROUTE) recordWelcomeStar()
                    is Effect.Record -> {
                        val event = effect.event
                        if (event is RecordEvent.Open &&
                            event.surface == SurfaceKind.TEST &&
                            event.outcome == OpenOutcome.LEFT
                        ) {
                            recordWelcomeStar()
                        }
                    }

                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            guardController.state.collect { engineState ->
                if (engineState.prompt?.kind == SurfaceKind.TEST) testSurfaceSeen = true
            }
        }
    }

    /** Records the first star exactly once, then shows the success state on the TRY_IT step. */
    private suspend fun recordWelcomeStar() {
        if (mutableState.value.testResult is SelfTestResult.Works) return
        val packageName = testedPackage ?: mutableState.value.apps
            .firstOrNull { mutableState.value.selected.contains(it.packageName) }
            ?.packageName
            ?: return
        if (!settings.prefs.first().welcomeStarGiven) {
            val now = clock.now()
            starRepository.insert(
                StarAward(
                    kind = StarKind.WELCOME,
                    litWall = now.wallMs,
                    logicalDay = LogicalDays.of(now.wallMs, now.zoneId),
                    window = WindowKind.NONE,
                    sourcePackage = packageName,
                    sourceRef = null,
                )
            )
            settings.updatePrefs { it.copy(welcomeStarGiven = true) }
        }
        mutableState.update { it.copy(testResult = SelfTestResult.Works(packageName), testing = false) }
    }

    /** Opens a package's launcher activity. @return false when the package cannot be launched. */
    private fun launchPackage(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            // A package that vanished between the query and the launch: the step reports it.
            false
        }
    }

    private companion object {
        /** The route the wizard asks the engine to return to when the test surface is answered. */
        const val RETURN_ROUTE: String = "onboarding"

        /** The four steps Sadiq will not answer for the user. */
        val CHOICE_STEPS: List<StepId> = listOf(StepId.WELCOME, StepId.APPS, StepId.MODE, StepId.TIMES)

        /** Slack over the engine's arm window, so a prompt at the last millisecond still counts. */
        const val ARM_GRACE_MS: Long = 1_500L
    }
}
