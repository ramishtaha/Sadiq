package com.ramish.sadiq.testing

import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.ThemeMode
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.sound.SoundKind
import com.ramish.sadiq.core.strict.SettingsChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The §3.4 defaults, as one value, so a test that only cares about one preference can ignore the rest. */
val DefaultTestPrefs: UiPrefs = UiPrefs(
    themeMode = ThemeMode.SYSTEM,
    reduceMotion = false,
    onboardingDone = false,
    onboardingStep = 0,
    keepAlive = true,
    weeklyNotif = true,
    focusDefaultMin = 25,
    focusDefaultSound = SoundKind.NONE,
    recentsLockedConfirmed = false,
    autostartConfirmed = false,
    batterySaverConfirmed = false,
    skySeed = 20_261_029L,
    welcomeStarGiven = false
)

/**
 * A [SettingsRepository] held in memory.
 *
 * Screens are tested through the repository they actually use, so this fake keeps both flows live and
 * records every `apply` — a test asserts that a tap produced exactly one [SettingsChange], rather than
 * that some callback fired.
 *
 * @param initialConfig the settings the app starts with.
 * @param initialPrefs the preferences the app starts with.
 */
class FakeSettingsRepository(
    initialConfig: GuardConfig = GuardConfig(),
    initialPrefs: UiPrefs = DefaultTestPrefs
) : SettingsRepository {

    private val mutableConfig = MutableStateFlow(initialConfig)
    private val mutablePrefs = MutableStateFlow(initialPrefs)

    override val config: StateFlow<GuardConfig> = mutableConfig.asStateFlow()
    override val prefs: Flow<UiPrefs> = mutablePrefs.asStateFlow()

    /** Every change passed to [apply], in order. */
    val applied: MutableList<SettingsChange> = mutableListOf()

    /** What [apply] returns. [ApplyResult.Applied] unless a test needs the delayed path. */
    var applyResult: ApplyResult = ApplyResult.Applied

    /**
     * An optional hook that runs inside [apply], after the change is recorded and before it returns.
     *
     * A test uses it to hold a write open (`release.await()`), which is the slow-Room-open case, and
     * assert what the caller does while the transaction is still in flight.
     */
    var beforeApplyReturns: (suspend () -> Unit)? = null

    /** How many overlay fallbacks have been counted per logical day. */
    val overlayFallbacks: MutableMap<Int, Int> = mutableMapOf()

    override suspend fun apply(change: SettingsChange): ApplyResult {
        applied += change
        beforeApplyReturns?.invoke()
        return applyResult
    }

    override suspend fun updatePrefs(transform: (UiPrefs) -> UiPrefs) {
        mutablePrefs.value = transform(mutablePrefs.value)
    }

    override suspend fun bumpOverlayFallback(logicalDay: Int): Int {
        val next = (overlayFallbacks[logicalDay] ?: 0) + 1
        overlayFallbacks[logicalDay] = next
        return next
    }

    /** Replace the settings, as an applied change would. @param config the new settings. */
    fun setConfig(config: GuardConfig) {
        mutableConfig.value = config
    }

    /** Replace the preferences. @param prefs the new preferences. */
    fun setPrefs(prefs: UiPrefs) {
        mutablePrefs.value = prefs
    }

    /** The current preferences, without collecting the flow. */
    fun currentPrefs(): UiPrefs = mutablePrefs.value
}
