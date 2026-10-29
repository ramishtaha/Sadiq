package com.ramish.sadiq.ui.guardtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())

    /** Apps the user explicitly un-ticked, so saving can remove them instead of re-adding them. */
    private val _removed = MutableStateFlow<Set<String>>(emptySet())

    /** Every launchable app's label, held outside the query filter so a save never depends on it. */
    @Volatile
    private var installedLabels: Map<String, String> = emptyMap()

    val uiState: StateFlow<AppPickerUiState> = combine(
        _query,
        settingsRepository.config.map { it.apps.map { it.packageName }.toSet() },
        _selectedPackages,
        flow {
            val apps = installedAppsSource.launchableApps().filter { it.packageName !in AppCatalog.baseNeverGuard }
            installedLabels = apps.associate { it.packageName to it.label }
            emit(apps)
        },
    ) { query, configGuardedPackages, selectedPackages, installedApps ->
        val filteredApps = installedApps.filter { it.label.contains(query, ignoreCase = true) }

        val suggestedFeeds = AppCatalog.knownFeedsFirst(
            items = filteredApps.filter { it.isSuggestedFeed },
            packageOf = { it.packageName }
        ).map { appInfo ->
            AppRow(
                packageName = appInfo.packageName,
                label = appInfo.label,
                isSelected = configGuardedPackages.contains(appInfo.packageName) || selectedPackages.contains(appInfo.packageName)
            )
        }

        val allOtherApps = filteredApps.filter { !it.isSuggestedFeed }.map { appInfo ->
            AppRow(
                packageName = appInfo.packageName,
                label = appInfo.label,
                isSelected = configGuardedPackages.contains(appInfo.packageName) || selectedPackages.contains(appInfo.packageName)
            )
        }

        AppPickerUiState(
            query = query,
            suggestedApps = suggestedFeeds,
            allApps = allOtherApps,
            selectedPackages = selectedPackages.ifEmpty { configGuardedPackages },
            loading = false,
            installedAppsSource = installedAppsSource,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPickerUiState()
    )

    init {
        viewModelScope.launch {
            settingsRepository.config.collect { config ->
                _selectedPackages.value = config.apps.map { it.packageName }.toSet()
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onAppToggle(packageName: String, label: String, isSelected: Boolean) {
        if (isSelected) {
            _selectedPackages.value = _selectedPackages.value.plus(packageName)
            _removed.value = _removed.value.minus(packageName)
        } else {
            _selectedPackages.value = _selectedPackages.value.minus(packageName)
            _removed.value = _removed.value.plus(packageName)
        }
    }

    fun saveGuardedApps() {
        viewModelScope.launch {
            // The saved set is the full selection, not the rows the search box happens to show.
            // Saving the filtered rows used to un-guard every app the user had searched past.
            val guarded = settingsRepository.config.value.apps.map { it.packageName }.toSet()
            val wanted = (guarded + _selectedPackages.value) - _removed.value
            val apps = wanted.map { pkg -> GuardedApp(pkg, installedLabels[pkg] ?: pkg) }
            settingsRepository.apply(SettingsChange.SetApps(apps))
        }
    }
}