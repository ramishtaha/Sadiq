package com.ramish.sadiq.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.withTransaction
import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.ChangeGate
import com.ramish.sadiq.contract.GateDecision
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.strict.isSupersededBy
import com.ramish.sadiq.core.strict.rebasedOnto
import com.ramish.sadiq.core.strict.withRemovalsRecorded
import com.ramish.sadiq.data.db.ConfigDao
import com.ramish.sadiq.data.db.GuardedDomainEntity
import com.ramish.sadiq.data.db.SadiqDatabase
import com.ramish.sadiq.data.db.toEntity
import com.ramish.sadiq.data.db.toGuardedApp
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Settings = one Preferences DataStore (the scalar preferences) + two Room tables (guarded apps and
 * domains). Both are folded into a hot [config] so the engine can read the current guard config
 * synchronously from any thread.
 *
 * [apply] is the only write path for guard config: it asks the optional [ChangeGate] first (strict
 * mode, M2). Without a gate — which is every M1 build — the change lands immediately.
 *
 * A deferred edit is not stored as it arrived. A `SetApps` snapshot gains the packages it drops
 * ([queuedRowFor]), so the applier can rebase it onto the list in force instead of replacing it
 * (AUDIT-4 A4-12), and an edit of the same kind that a newer one has replaced is dropped
 * ([dropSuperseded]).
 *
 * CONTRACT-GAP: the contract has no application-scope CoroutineScope binding, so this singleton owns
 * a supervisor scope for its own lifetime and starts the config flow in it eagerly, as WP-09 asks.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val configDao: ConfigDao,
    private val database: SadiqDatabase,
    private val pendingChanges: PendingChangesRepository,
    private val changeGate: Optional<ChangeGate>,
) : SettingsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val seedMutex = Mutex()

    private val mutableConfig = MutableStateFlow(GuardConfig())

    override val config: StateFlow<GuardConfig> = mutableConfig.asStateFlow()

    private val settingsFlow: Flow<Preferences> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { stored -> ensureSkySeed(stored) }

    override val prefs: Flow<UiPrefs> = settingsFlow.map { stored -> PrefsMapping.readUiPrefs(stored) }

    init {
        // Eager by design: the engine reads `config.value` on the first accessibility event, which
        // can arrive before any screen has collected the flow.
        scope.launch {
            combine(
                settingsFlow,
                configDao.observeApps(),
                configDao.observeDomains(),
            ) { stored, apps, domains ->
                PrefsMapping.readConfig(
                    prefs = stored,
                    apps = apps.map { it.toGuardedApp() },
                    domains = domains.mapTo(mutableSetOf()) { it.domain },
                )
            }.collect { config -> mutableConfig.value = config }
        }
    }

    override suspend fun apply(change: SettingsChange): ApplyResult {
        val nowWall = System.currentTimeMillis()
        val decision = changeGate.orElse(null)?.gate(config.value, change, nowWall) ?: GateDecision.ApplyNow
        return when (decision) {
            GateDecision.ApplyNow -> {
                applyNow(change, nowWall)
                ApplyResult.Applied
            }

            is GateDecision.Defer -> {
                pendingChanges.add(queuedRowFor(change), decision.applyAtWall)
                ApplyResult.Scheduled(decision.applyAtWall)
            }
        }
    }

    override suspend fun updatePrefs(transform: (UiPrefs) -> UiPrefs) {
        dataStore.edit { mutable ->
            val next = transform(PrefsMapping.readUiPrefs(mutable))
            PrefsMapping.writeUiPrefs(mutable, next)
        }
    }

    override suspend fun bumpOverlayFallback(logicalDay: Int): Int {
        var count = 1
        dataStore.edit { mutable ->
            val sameDay = mutable[SettingsKeys.OVERLAY_FALLBACK_DAY] == logicalDay
            count = if (sameDay) (mutable[SettingsKeys.OVERLAY_FALLBACK_COUNT] ?: 0) + 1 else 1
            mutable[SettingsKeys.OVERLAY_FALLBACK_DAY] = logicalDay
            mutable[SettingsKeys.OVERLAY_FALLBACK_COUNT] = count
        }
        return count
    }

    /**
     * Writes one change, or defers it. [nowWall] stamps the pending row and is the wall time the
     * gate budgets against.
     *
     * Wall time comes from the platform clock rather than the engine's `Clock`: this repository must
     * assemble before WP-13's Clock binding exists, and its stamps only place strict-mode deferrals.
     * The engine's own clock stays authoritative for everything the reducer decides.
     */
    private suspend fun applyNow(change: SettingsChange, nowWall: Long) {
        when (change) {
            is SettingsChange.SetMode -> dataStore.edit { mutable ->
                PrefsMapping.writeMode(mutable, change.mode)
            }

            is SettingsChange.SetApps -> replaceApps(change, nowWall)

            is SettingsChange.SetSurfaceMode -> configDao.setSurfaceMode(change.packageName, change.mode)

            is SettingsChange.SetDomains -> replaceDomains(change.domains, nowWall)

            is SettingsChange.SetPrayer -> dataStore.edit { mutable ->
                PrefsMapping.writePrayer(mutable, change.cfg)
            }

            is SettingsChange.SetSleepGate -> dataStore.edit { mutable ->
                PrefsMapping.writeSleepGate(mutable, change.cfg)
            }

            is SettingsChange.SetMorning -> dataStore.edit { mutable ->
                PrefsMapping.writeMorning(mutable, change.cfg)
            }

            is SettingsChange.SetRamadan -> dataStore.edit { mutable ->
                PrefsMapping.writeRamadan(mutable, change.cfg)
            }

            is SettingsChange.SetStrict -> dataStore.edit { mutable ->
                PrefsMapping.writeStrict(mutable, change.cfg)
            }

            is SettingsChange.SetFlags -> dataStore.edit { mutable ->
                PrefsMapping.writeFlags(mutable, change.reflection, change.gymHint, change.webGuard)
            }
        }
        dropSuperseded(change, nowWall)
    }

    /**
     * The row to store for a deferred edit.
     *
     * A `SetApps` snapshot is taken apart on the way **into** the queue: the packages it drops are
     * recorded on it, because the list it will be compared against is not the list it was built from
     * (AUDIT-4 A4-12). Recording it here is the only moment the user's own list is still in hand —
     * ten minutes later, the apps guarded then look exactly like apps added since.
     *
     * Every other change type is a single value and is stored as it stands.
     */
    private fun queuedRowFor(change: SettingsChange): SettingsChange = when (change) {
        is SettingsChange.SetApps -> change.withRemovalsRecorded(config.value.apps)
        else -> change
    }

    /**
     * Drop a queued row that a newer edit of the same kind has made pointless.
     *
     * Second half of A4-12. A queued `SetMode(GENTLE)` otherwise undoes, ten minutes later, the Firm
     * the user chose while it waited — the same staleness one type over. A `SetApps` row is only
     * dropped when the list just written already drops everything it meant to remove; if the new
     * list still guards one of those packages, the row keeps its own moment and is rebased onto that
     * list by [replaceApps].
     *
     * Two guards keep this from eating a row it should not:
     * - only rows **still waiting** are dropped (`applyAt` after [nowWall]); a row due in the same
     *   pass has already outlived the edit and lands in its own order;
     * - `due(Long.MAX_VALUE)` is the contract's only synchronous read of the queue, so it is the one
     *   used here rather than adding a second query to the interface.
     */
    private suspend fun dropSuperseded(applied: SettingsChange, nowWall: Long) {
        try {
            for (row in pendingChanges.due(Long.MAX_VALUE)) {
                if (row.applyAt > nowWall && row.change.isSupersededBy(applied)) {
                    pendingChanges.remove(row.id)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The edit itself has already landed, so this must not fail the write the user is
            // waiting on. A row that survives is only stale: it is rebased when its own moment
            // comes, which cannot un-guard anything.
            Log.w(TAG, "queued rows not superseded: ${failure.message}", failure)
        }
    }

    /**
     * Writes the guard list, atomically, and keeps the original `added_at` for apps that stay.
     *
     * A live edit replaces the table with [SettingsChange.SetApps.apps]. A **queued** snapshot
     * (`removed != null`) is rebased instead: `(current ∪ snapshot) − removed`, computed from the
     * rows inside this transaction so the subtraction is against the table that is really there, not
     * against a cached config that a write may already have overtaken (AUDIT-4 A4-12).
     *
     * The live row wins for any package in both lists: that keeps its surface mode, so a snapshot
     * that waited cannot revert a `SetSurfaceMode` the user made in the meantime.
     */
    private suspend fun replaceApps(change: SettingsChange.SetApps, nowWall: Long) {
        database.withTransaction {
            val existing = configDao.appsOnce()
            val addedAt = existing.associate { row -> row.packageName to row.addedAt }
            val apps = if (change.removed == null) {
                change.apps
            } else {
                change.rebasedOnto(existing.map { row -> row.toGuardedApp() })
            }
            configDao.clearApps()
            configDao.insertApps(
                apps.map { app -> app.toEntity(addedAt = addedAt[app.packageName] ?: nowWall) },
            )
        }
    }

    private suspend fun replaceDomains(domains: Set<String>, nowWall: Long) {
        database.withTransaction {
            val existing = configDao.domainsOnce().associate { row -> row.domain to row.addedAt }
            configDao.clearDomains()
            configDao.insertDomains(
                domains.map { domain ->
                    GuardedDomainEntity(
                        domain = domain,
                        addedAt = existing[domain] ?: nowWall,
                    )
                },
            )
        }
    }

    /**
     * `sky_seed` must exist before the first sky is drawn and must never change afterwards, so the
     * first reader creates it. The mutex plus the re-check inside `edit` make concurrent first reads
     * agree on a single seed.
     */
    private suspend fun ensureSkySeed(stored: Preferences): Preferences {
        if (stored[SettingsKeys.SKY_SEED] != null) return stored
        return seedMutex.withLock {
            dataStore.edit { mutable ->
                if (mutable[SettingsKeys.SKY_SEED] == null) {
                    mutable[SettingsKeys.SKY_SEED] = Random.nextLong()
                }
            }
        }
    }

    private companion object {
        const val TAG = "SP/Settings"
    }
}
