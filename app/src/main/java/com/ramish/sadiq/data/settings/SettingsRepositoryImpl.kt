package com.ramish.sadiq.data.settings

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
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange
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
                pendingChanges.add(change, decision.applyAtWall)
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

            is SettingsChange.SetApps -> replaceApps(change.apps, nowWall)

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
    }

    /** Replaces the guard list atomically and keeps the original `added_at` for apps that stay. */
    private suspend fun replaceApps(apps: List<GuardedApp>, nowWall: Long) {
        database.withTransaction {
            val existing = configDao.appsOnce().associate { row -> row.packageName to row.addedAt }
            configDao.clearApps()
            configDao.insertApps(
                apps.map { app -> app.toEntity(addedAt = existing[app.packageName] ?: nowWall) },
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
}
