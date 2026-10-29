package com.ramish.sadiq.ui.guardtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppPickerUiState(
    val query: String = "",
    val suggestedApps: List<AppRow> = emptyList(),
    val allApps: List<AppRow> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val loading: Boolean = true,
    val installedAppsSource: InstalledAppsSource? = null,
)

data class AppRow(
    val packageName: String,
    val label: String,
    val isSelected: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppPickerViewModel @Inject constructor(
    private val installedAppsSource: InstalledAppsSource,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /**
     * The apps he ticked himself, held as a delta rather than as a copy of the selection.
     *
     * The field this replaces was overwritten with the config's package set on **every** config
     * emission, so a "Think it over" change landing, or the mode changing, while he was mid-edit
     * un-ticked every app he had just ticked. A delta cannot be erased by an emission: a new tick
     * stays ticked until he un-ticks it. The config half of the selection is read live in [uiState].
     */
    private val _added = MutableStateFlow<Set<String>>(emptySet())

    /** Apps the user explicitly un-ticked, so saving can remove them instead of re-adding them. */
    private val _removed = MutableStateFlow<Set<String>>(emptySet())

    /** Every launchable app's label, held outside the query filter so a save never depends on it. */
    @Volatile
    private var installedLabels: Map<String, String> = emptyMap()

    /**
     * True while a save's write is open.
     *
     * Only touched on the main thread, from [saveGuardedApps]. A second tap before the first write
     * returns would queue a second save and a second pop, and the second pop would take him past
     * the Guard tab.
     */
    private var saveInFlight: Boolean = false

    val uiState: StateFlow<AppPickerUiState> = combine(
        _query,
        settingsRepository.config.map { it.apps.map { it.packageName }.toSet() },
        _added,
        _removed,
        flow {
            val apps = installedAppsSource.launchableApps().filter { it.packageName !in AppCatalog.baseNeverGuard }
            installedLabels = apps.associate { it.packageName to it.label }
            emit(apps)
        },
    ) { query, configGuardedPackages, added, removed, installedApps ->
        // A row is ticked only when it is guarded (by the config or his new tick) AND he has not
        // just un-ticked it. The config half is read live, so an app guarded by another screen shows
        // as ticked; the `added` half is his own pending tick and no emission can take it away.
        fun isOn(pkg: String): Boolean =
            pkg !in removed && (pkg in configGuardedPackages || pkg in added)

        val filteredApps = installedApps.filter { it.label.contains(query, ignoreCase = true) }

        val suggestedFeeds = AppCatalog.knownFeedsFirst(
            items = filteredApps.filter { it.isSuggestedFeed },
            packageOf = { it.packageName }
        ).map { appInfo ->
            AppRow(
                packageName = appInfo.packageName,
                label = appInfo.label,
                isSelected = isOn(appInfo.packageName)
            )
        }

        val allOtherApps = filteredApps.filter { !it.isSuggestedFeed }.map { appInfo ->
            AppRow(
                packageName = appInfo.packageName,
                label = appInfo.label,
                isSelected = isOn(appInfo.packageName)
            )
        }

        AppPickerUiState(
            query = query,
            suggestedApps = suggestedFeeds,
            allApps = allOtherApps,
            selectedPackages = (configGuardedPackages + added) - removed,
            loading = false,
            installedAppsSource = installedAppsSource,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPickerUiState()
    )

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onAppToggle(packageName: String, label: String, isSelected: Boolean) {
        if (isSelected) {
            _added.value = _added.value.plus(packageName)
            _removed.value = _removed.value.minus(packageName)
        } else {
            _added.value = _added.value.minus(packageName)
            _removed.value = _removed.value.plus(packageName)
        }
    }

    /**
     * Saves the whole selection, then reports what happened to it.
     *
     * The write used to be fire-and-forget while the screen popped at once. It runs in
     * [viewModelScope], which the nav entry's destruction cancels at the end of the exit transition,
     * so a slow first Room open rolled the transaction back and he was taken back to a Guard tab
     * that had not changed. [onDone] is the fix: it is called after [SettingsRepository.apply]
     * returned, on the main thread (`viewModelScope` is `Dispatchers.Main.immediate`, and a
     * suspension resumes on the dispatcher it started on), and the screen pops from there.
     *
     * A package that is already configured is written back as the same [GuardedApp], so its
     * `surfaceMode` survives. Rebuilding it from the package and label would silently reset a
     * `SHORTS_ONLY` app to `WHOLE_APP` on every save.
     *
     * @param onDone called with the [ApplyResult] once the change has been applied or scheduled.
     */
    fun saveGuardedApps(onDone: (ApplyResult) -> Unit) {
        if (saveInFlight) return
        saveInFlight = true
        viewModelScope.launch {
            try {
                // The saved set is the full selection, not the rows the search box happens to show.
                // Saving the filtered rows used to un-guard every app the user had searched past.
                // Zero is a valid selection: un-guarding the last app has to be possible.
                val current = settingsRepository.config.value.apps.associateBy { it.packageName }
                val wanted = (current.keys + _added.value) - _removed.value
                val apps = wanted.map { pkg ->
                    current[pkg] ?: GuardedApp(pkg, installedLabels[pkg] ?: pkg)
                }
                onDone(settingsRepository.apply(SettingsChange.SetApps(apps)))
            } finally {
                saveInFlight = false
            }
        }
    }
}
