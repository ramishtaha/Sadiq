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
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.strict.rebasedOnto
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
 * A deferred edit is not stored as it arrived. A `SetApps` snapshot gains the packages it drops and
 * loses the ones an earlier row is already dropping ([queuedRowFor]), so the applier can rebase it
 * onto the list in force instead of replacing it (AUDIT-4 A4-12, AUDIT-5 A5-01/A5-02). Every write
 * that lands then composes the rows still waiting ([replaceApps]): what it removed is struck out of
 * their snapshots, and what it guarded cancels a queued removal of the same app. An edit that a
 * newer one has replaced is dropped field by field, and one that survives is re-cut against the newer
 * write ([settleWaitingEdits]).
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
        // Captured once: both the gate and the supersession check measure an edit against the
        // settings it was made against, and the read must not move under either of them.
        val before = config.value
        val decision = changeGate.orElse(null)?.gate(before, change, nowWall) ?: GateDecision.ApplyNow
        return when (decision) {
            GateDecision.ApplyNow -> {
                applyNow(change, nowWall, before)
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
     * gate budgets against; [before] is the settings it was judged against.
     *
     * Wall time comes from the platform clock rather than the engine's `Clock`: this repository must
     * assemble before WP-13's Clock binding exists, and its stamps only place strict-mode deferrals.
     * The engine's own clock stays authoritative for everything the reducer decides.
     */
    private suspend fun applyNow(change: SettingsChange, nowWall: Long, before: GuardConfig) {
        // The list this write actually left in the table. Only a `SetApps` has one, and only it can
        // supersede another app list, so the empty default never hides a real answer.
        var written: List<GuardedApp> = emptyList()
        when (change) {
            is SettingsChange.SetMode -> dataStore.edit { mutable ->
                PrefsMapping.writeMode(mutable, change.mode)
            }

            is SettingsChange.SetApps -> written = replaceApps(change, nowWall)

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
        settleWaitingEdits(change, nowWall, before, written)
    }

    /**
     * The row to store for a deferred edit.
     *
     * A `SetApps` snapshot is taken apart on the way **into** the queue: the packages it drops are
     * recorded on it, because the list it will be compared against is not the list it was built from
     * (AUDIT-4 A4-12). Recording it here is the only moment the user's own list is still in hand —
     * ten minutes later, the apps guarded then look exactly like apps added since.
     *
     * Two AUDIT-5 corrections to that record:
     * - it is taken from the table (`appsOnce`) rather than the cached `config`, which a write may
     *   already have overtaken;
     * - it leaves out anything an earlier waiting row is already dropping, and the snapshot loses
     *   those packages too, so two rows cannot each claim the same removal (A5-01) and the second
     *   cannot re-add what the first will take away (A5-02). See [deferredAgainst].
     *
     * Every other change type is a single value and is stored as it stands.
     */
    private suspend fun queuedRowFor(change: SettingsChange): SettingsChange = when (change) {
        is SettingsChange.SetApps -> change.deferredAgainst(
            guardedNow = configDao.appsOnce().map { row -> row.toGuardedApp() },
            alreadyQueuedRemovals = pendingChanges.due(Long.MAX_VALUE).queuedRemovals(),
        )

        else -> change
    }

    /**
     * Settle every waiting edit against the one that has just landed.
     *
     * Second half of A4-12, corrected by AUDIT-5 A5-03 and A5-10. A queued `SetMode(GENTLE)`
     * otherwise undoes, ten minutes later, the Firm the user chose while it waited — the same
     * staleness one type over.
     *
     * An app list is judged against [written] — the list the write actually left in the table — and
     * never against `applied.apps`. When the applied change is itself a queued row, its snapshot is
     * older than the removal the user made after it, so judging by it deleted a removal he was still
     * waiting for. Every other type is judged field by field ([isMadePointlessBy]): an edit that
     * moved the Ramadan offset must not delete a queued Ramadan mode change.
     *
     * A row that survives is then re-cut against the newer edit ([rebasedBehind]), so it carries its
     * own field and takes the newer value for the rest. Without that, landing the queued "Ramadan
     * off" would put the offset he had just corrected back to zero.
     *
     * Two guards keep this from eating a row it should not:
     * - only rows **still waiting** are touched (`applyAt` after [nowWall]); a row due in the same
     *   pass has already outlived the edit and lands in its own order;
     * - `due(Long.MAX_VALUE)` is the contract's only synchronous read of the queue, so it is the one
     *   used here rather than adding a second query to the interface.
     */
    private suspend fun settleWaitingEdits(
        applied: SettingsChange,
        nowWall: Long,
        before: GuardConfig,
        written: List<GuardedApp>
    ) {
        try {
            for (row in pendingChanges.due(Long.MAX_VALUE)) {
                if (row.applyAt <= nowWall) continue
                if (row.change.isMadePointlessBy(applied, before, written)) {
                    pendingChanges.remove(row.id)
                    continue
                }
                val recut = row.change.rebasedBehind(applied, before)
                if (recut != row.change) {
                    pendingChanges.remove(row.id)
                    pendingChanges.add(recut, row.applyAt)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The edit itself has already landed, so this must not fail the write the user is
            // waiting on. A row that survives is only stale: it is rebased when its own moment
            // comes, which cannot un-guard anything.
            Log.w(TAG, "queued rows not settled: ${failure.message}", failure)
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
     *
     * The rows still waiting are then composed with what was written ([rewritesAfterAppWrite]): what
     * this write stopped guarding is struck out of their snapshots, and — for a live write — what it
     * started guarding cancels a queued removal of that package. That is what keeps a snapshot from
     * meaning something different by the time it lands; without it a stale snapshot counts an
     * earlier row's removal as an addition of its own (A5-02) and can un-guard an app he re-added
     * (A5-01). A row that is already due is left alone: the applier composes the due batch itself.
     *
     * @return the list actually written, for the supersession check that follows.
     */
    private suspend fun replaceApps(change: SettingsChange.SetApps, nowWall: Long): List<GuardedApp> {
        var written: List<GuardedApp>? = null
        database.withTransaction {
            val rows = configDao.appsOnce()
            val existing = rows.map { row -> row.toGuardedApp() }
            val addedAt = rows.associate { row -> row.packageName to row.addedAt }
            val apps = if (change.removed == null) change.apps else change.rebasedOnto(existing)
            configDao.clearApps()
            configDao.insertApps(
                apps.map { app -> app.toEntity(addedAt = addedAt[app.packageName] ?: nowWall) },
            )
            written = apps
            composeWaitingRows(
                before = existing,
                written = apps,
                nowWall = nowWall,
                live = change.removed == null,
            )
        }
        return written ?: emptyList()
    }

    /**
     * Rewrites every waiting app row that the write just made stale.
     *
     * The queue contract has no update, so a rewrite is `remove` + `add` at the row's own `applyAt`:
     * the row keeps its moment and its meaning, and only its id changes. A failure here must not fail
     * the write the user is waiting on — a row left un-composed is still rebased when it lands, which
     * can re-add an app but can never remove one — so it is logged and the write stands.
     */
    private suspend fun composeWaitingRows(
        before: List<GuardedApp>,
        written: List<GuardedApp>,
        nowWall: Long,
        live: Boolean
    ) {
        try {
            val rewrites = pendingChanges.due(Long.MAX_VALUE)
                .rewritesAfterAppWrite(before, written, nowWall, live)
            for (rewrite in rewrites) {
                pendingChanges.remove(rewrite.row.id)
                pendingChanges.add(rewrite.replacement, rewrite.row.applyAt)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "waiting app rows not composed: ${failure.message}", failure)
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
