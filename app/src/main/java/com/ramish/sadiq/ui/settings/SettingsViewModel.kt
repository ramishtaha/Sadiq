package com.ramish.sadiq.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.BuildConfig
import com.ramish.sadiq.contract.HistoryRepository
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.StarRepository
import com.ramish.sadiq.contract.ThemeMode
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.core.model.PrayerConfig
import com.ramish.sadiq.core.strict.SettingsChange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the Settings screen draws, in one value.
 *
 * @property versionName `versionName` from the build, shown in About.
 * @property sleepEnabled the Bedtime switch; the same edit the Guard tab makes.
 * @property sleepStartMinuteOfDay Bedtime's start, minutes after midnight.
 * @property morningEnabled the "Your morning" switch.
 * @property morningEndMinuteOfDay when the morning window ends, minutes after midnight.
 * @property prayerSummary the one-line method/place summary that opens the prayer screen.
 * @property showLiveUpdates whether this Android version has promoted ongoing notifications.
 * @property confirmDelete whether the delete confirmation sheet is up.
 * @property deleting whether the two deletes are in flight; the sheet's button is disabled meanwhile.
 * @property historyDeleted a completed delete, so the screen can say it happened once.
 */
data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val keepAlive: Boolean = true,
    val weeklyNotif: Boolean = true,
    val versionName: String = "",
    val sleepEnabled: Boolean = true,
    val sleepStartMinuteOfDay: Int = 1350,
    val morningEnabled: Boolean = true,
    val morningEndMinuteOfDay: Int = 570,
    val prayerSummary: String = "",
    val showLiveUpdates: Boolean = false,
    val confirmDelete: Boolean = false,
    val deleting: Boolean = false,
    val historyDeleted: Boolean = false
)

/**
 * The Settings screen's intents.
 *
 * Preferences go through [SettingsRepository.updatePrefs] and guard settings through
 * [SettingsRepository.apply], never the other way round: `apply` is the only path that can be
 * delayed by "Think it over", and `updatePrefs` is the only path that can write a theme.
 *
 * "Delete all history" removes stars and the five history tables together and touches no setting,
 * which is why it needs both repositories and a confirmation before either is called.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val stars: StarRepository,
    private val history: HistoryRepository,
    private val launcher: OemSettingsLauncher,
    permissions: PermissionStatusProvider
) : ViewModel() {

    private val mutableState = MutableStateFlow(SettingsUiState(versionName = BuildConfig.VERSION_NAME))

    /** The screen state. Emits once the stored preferences are read. */
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        mutableState.update { it.copy(showLiveUpdates = permissions.snapshot().sdkInt >= PROMOTED_NOTIFICATIONS_SDK) }

        viewModelScope.launch {
            settings.prefs.collect { prefs ->
                mutableState.update {
                    it.copy(
                        theme = prefs.themeMode,
                        reduceMotion = prefs.reduceMotion,
                        keepAlive = prefs.keepAlive,
                        weeklyNotif = prefs.weeklyNotif
                    )
                }
            }
        }

        viewModelScope.launch {
            settings.config.collect { config ->
                mutableState.update {
                    it.copy(
                        sleepEnabled = config.sleepGate.enabled,
                        sleepStartMinuteOfDay = config.sleepGate.startMinuteOfDay,
                        morningEnabled = config.morning.enabled,
                        morningEndMinuteOfDay = config.morning.endMinuteOfDay,
                        prayerSummary = prayerSummary(config.prayer)
                    )
                }
            }
        }
    }

    /** Choose a palette. @param mode the new preference. */
    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            settings.updatePrefs { prefs -> prefs.copy(themeMode = mode) }
        }
    }

    /** @param reduce true to shorten or remove animations. */
    fun setReduceMotion(reduce: Boolean) {
        viewModelScope.launch {
            settings.updatePrefs { prefs -> prefs.copy(reduceMotion = reduce) }
        }
    }

    /** @param keepAlive true to keep the foreground service running for timers. */
    fun setKeepAlive(keepAlive: Boolean) {
        viewModelScope.launch {
            settings.updatePrefs { prefs -> prefs.copy(keepAlive = keepAlive) }
        }
    }

    /** @param enabled true to receive the Sunday review note. */
    fun setWeeklyReview(enabled: Boolean) {
        viewModelScope.launch {
            settings.updatePrefs { prefs -> prefs.copy(weeklyNotif = enabled) }
        }
    }

    /** Turn the Bedtime window on or off, keeping its start time. @param enabled the switch. */
    fun setBedtimeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.apply(SettingsChange.SetSleepGate(settings.config.value.sleepGate.copy(enabled = enabled)))
        }
    }

    /** Turn the morning window on or off, keeping its end time. @param enabled the switch. */
    fun setMorningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.apply(SettingsChange.SetMorning(settings.config.value.morning.copy(enabled = enabled)))
        }
    }

    /** Open the keep-alive channel's own page, where it can be silenced. */
    fun openNotificationChannel() {
        launcher.open(SettingsTarget.CHANNEL_GUARD_STATUS)
    }

    /** Open the promoted-notifications page, for the Live updates row. */
    fun openLiveUpdates() {
        launcher.open(SettingsTarget.LIVE_UPDATES)
    }

    /** Ask before deleting: nothing is removed until [confirmDelete]. */
    fun requestDeleteHistory() {
        if (mutableState.value.deleting) return
        mutableState.update { it.copy(confirmDelete = true) }
    }

    /** Close the confirmation without deleting. */
    fun dismissDeleteHistory() {
        mutableState.update { it.copy(confirmDelete = false) }
    }

    /**
     * Delete the sky and the history, then say so.
     *
     * Both calls are suspending and both must finish before the screen claims anything: the stars and
     * the events are separate stores, and a half-finished delete that reported success would be the
     * one lie this app cannot tell.
     */
    fun confirmDeleteHistory() {
        if (mutableState.value.deleting) return
        mutableState.update { it.copy(deleting = true, historyDeleted = false) }
        viewModelScope.launch {
            stars.deleteAll()
            history.deleteAll()
            mutableState.update { it.copy(deleting = false, confirmDelete = false, historyDeleted = true) }
        }
    }

    private companion object {
        /** `canPostPromotedNotifications` landed in Android 16 (API 36). */
        const val PROMOTED_NOTIFICATIONS_SDK: Int = 36
    }
}

/**
 * "Karachi · Hanafi · Thane, Maharashtra", the line under the Prayer times row.
 *
 * @param prayer the settings in force.
 */
internal fun prayerSummary(prayer: PrayerConfig): String =
    "${methodLabel(prayer.method)} · ${madhabLabel(prayer.madhab)} · ${prayer.place}"

/** The method's short name, as the prayer screen's dropdown shows it. @param method the calculation method. */
internal fun methodLabel(method: CalcMethod): String = when (method) {
    CalcMethod.KARACHI -> "Karachi"
    CalcMethod.MUSLIM_WORLD_LEAGUE -> "Muslim World League"
    CalcMethod.EGYPTIAN -> "Egyptian"
    CalcMethod.UMM_AL_QURA -> "Umm al-Qura"
    CalcMethod.DUBAI -> "Dubai"
    CalcMethod.QATAR -> "Qatar"
    CalcMethod.KUWAIT -> "Kuwait"
    CalcMethod.SINGAPORE -> "Singapore"
    CalcMethod.NORTH_AMERICA -> "North America"
}

/** The Asr shadow rule's name. @param madhab the school. */
internal fun madhabLabel(madhab: Madhab): String = when (madhab) {
    Madhab.HANAFI -> "Hanafi"
    Madhab.SHAFI -> "Shafi'i"
}

/**
 * Minutes after midnight as `HH:mm`.
 *
 * 24-hour on purpose: the settings rows are read at a glance next to a prayer time, and a bare
 * "10:30 PM" beside "Fajr 05:21" makes the reader convert twice.
 *
 * @param minuteOfDay minutes after local midnight; values outside a day wrap.
 */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val wrapped = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    val hour = wrapped / 60
    val minute = wrapped % 60
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

private const val MINUTES_PER_DAY: Int = 24 * 60
