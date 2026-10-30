package com.ramish.sadiq.ui.guardtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.core.apps.AppCatalog
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.data.settings.rewritesCancellingRemovals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
    /**
     * Packages a queued "Think it over" edit is going to stop guarding, mapped to the wall time it
     * happens at. They render unticked — that is what he will actually have — and the moment is here
     * so the row can say when.
     */
    val pendingRemovals: Map<String, Long> = emptyMap(),
)

data class AppRow(
    val packageName: String,
    val label: String,
    val isSelected: Boolean,
)

/**
 * The selection, as he will have it, not as the table holds it.
 *
 * [guarded] is the live config, which keeps an app until the queued row that drops it lands.
 * [pendingRemovals] and [pendingAdditions] are the intent of the rows already waiting. Everything
 * the picker shows is read from the folded value, because a tick is a promise: an app whose removal
 * is queued must not render ticked (AUDIT-5 A5-01 — it did, so he un-ticked it again and the second
 * save recorded the same removal a second time).
 */
private data class GuardIntent(
    val guarded: Set<String>,
    val pendingRemovals: Map<String, Long>,
    val pendingAdditions: Set<String>,
) {
    /** What he will have if he saves nothing: the live list with the queue's intent folded in. */
    val effective: Set<String> get() = (guarded + pendingAdditions) - pendingRemovals.keys
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppPickerViewModel @Inject constructor(
    private val installedAppsSource: InstalledAppsSource,
    private val settingsRepository: SettingsRepository,
    private val pendingChanges: PendingChangesRepository,
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

    /** The live list and the queue's intent, folded once per emission of either. */
    private val intent: Flow<GuardIntent> = combine(
        settingsRepository.config.map { config ->
            config.apps.mapTo(mutableSetOf()) { app -> app.packageName }
        },
        pendingChanges.observe(),
    ) { guarded, rows -> guardIntent(guarded, rows) }

    val uiState: StateFlow<AppPickerUiState> = combine(
        _query,
        intent,
        _added,
        _removed,
        flow {
            val apps = installedAppsSource.launchableApps().filter { it.packageName !in AppCatalog.baseNeverGuard }
            installedLabels = apps.associate { it.packageName to it.label }
            emit(apps)
        },
    ) { query, intent, added, removed, installedApps ->
        // A row is ticked only when he will actually have it — the live list with the queue folded
        // in, or his own new tick — and he has not just un-ticked it. Reading the queue is what stops
        // an app whose removal is already pending from rendering ticked (A5-01).
        fun isOn(pkg: String): Boolean =
            pkg !in removed && (pkg in intent.effective || pkg in added)

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
            selectedPackages = (intent.effective + added) - removed,
            loading = false,
            installedAppsSource = installedAppsSource,
            pendingRemovals = intent.pendingRemovals,
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
     * The saved list is the live one plus his deltas — not the folded selection. A queued removal is
     * already on its way, and writing it out as a fresh loosening edit would make the save wait a
     * second ten minutes and delay an addition that has nothing to do with it. The one thing that
     * cannot be expressed as a list is a **re-tick**: an app whose removal is queued is still in the
     * table, so ticking it again is a no-op write, and only [cancelQueuedRemovals] can call the
     * removal off (A5-01).
     *
     * @param onDone called with the [ApplyResult] once the change has been applied or scheduled.
     */
    fun saveGuardedApps(onDone: (ApplyResult) -> Unit) {
        if (saveInFlight) return
        saveInFlight = true
        viewModelScope.launch {
            try {
                // Before the write, so a row queued by this save is recorded against a queue that no
                // longer plans to remove what he just kept.
                cancelQueuedRemovals(_added.value)

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

    /**
     * Calls off a queued removal of an app he has just ticked again.
     *
     * The picker is the only place that knows a tick was a decision rather than an inherited state:
     * with the queue folded into the rows, an app whose removal is waiting shows unticked, so a tick
     * on it is always his own. The row is rewritten without that package in its removal record — and
     * kept, because it may still be adding an app — so nothing un-guards what he just kept.
     */
    private suspend fun cancelQueuedRemovals(reticked: Set<String>) {
        if (reticked.isEmpty()) return
        val rewrites = pendingChanges.due(Long.MAX_VALUE).rewritesCancellingRemovals(reticked)
        for (rewrite in rewrites) {
            pendingChanges.remove(rewrite.row.id)
            pendingChanges.add(rewrite.replacement, rewrite.row.applyAt)
        }
    }
}

/**
 * Folds the waiting rows into one intent.
 *
 * A `SetApps` row can say two things: it will drop these packages ([SettingsChange.SetApps.removed])
 * and it will guard those in its snapshot that the table does not have yet. The first occurrence of
 * a removal wins, because rows arrive earliest-first and that is the moment it actually happens.
 */
private fun guardIntent(guarded: Set<String>, rows: List<PendingChange>): GuardIntent {
    val removals = linkedMapOf<String, Long>()
    val additions = mutableSetOf<String>()
    for (row in rows) {
        val change = row.change as? SettingsChange.SetApps ?: continue
        change.removed.orEmpty().forEach { pkg ->
            if (pkg !in removals) removals[pkg] = row.applyAt
        }
        change.apps.forEach { app ->
            if (app.packageName !in guarded) additions += app.packageName
        }
    }
    return GuardIntent(guarded = guarded, pendingRemovals = removals, pendingAdditions = additions)
}
