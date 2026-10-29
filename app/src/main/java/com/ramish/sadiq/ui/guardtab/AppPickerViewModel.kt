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

    val uiState: StateFlow<AppPickerUiState> = combine(
        _query,
        settingsRepository.config.map { it.apps.map { it.packageName }.toSet() },
        _selectedPackages,
        flow {
            emit(installedAppsSource.launchableApps().filter { it.packageName !in AppCatalog.baseNeverGuard })
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
        _selectedPackages.value = if (isSelected) {
            _selectedPackages.value.plus(packageName)
        } else {
            _selectedPackages.value.minus(packageName)
        }
    }

    fun saveGuardedApps() {
        viewModelScope.launch {
            val currentApps = uiState.value.suggestedApps.filter { it.isSelected }.map { GuardedApp(it.packageName, it.label) } +
                    uiState.value.allApps.filter { it.isSelected }.map { GuardedApp(it.packageName, it.label) }
            settingsRepository.apply(SettingsChange.SetApps(currentApps))
        }
    }
}