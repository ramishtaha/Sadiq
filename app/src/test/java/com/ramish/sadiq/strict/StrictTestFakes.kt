package com.ramish.sadiq.strict

import com.ramish.sadiq.contract.ApplyResult
import com.ramish.sadiq.contract.ChangeGate
import com.ramish.sadiq.contract.GateDecision
import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.contract.SettingsRepository
import com.ramish.sadiq.contract.UiPrefs
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.strict.isSupersededBy
import com.ramish.sadiq.core.strict.rebasedOnto
import com.ramish.sadiq.core.strict.withRemovalsRecorded
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.testing.DefaultTestPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [SettingsRepository] with the real write path: every [apply] asks the [ChangeGate] first, and a
 * deferred edit is stored in [pendingChanges] instead of the config.
 *
 * That is the whole of `SettingsRepositoryImpl.apply` in nine lines, which is what makes these tests
 * meaningful — the applier is exercised against a repository that really does re-ask the gate, so a
 * missing bypass would show up as a change that never lands rather than as a green test.
 *
 * The three A4-12 pieces are mirrored too, because they are the behaviour under test: a deferred
 * `SetApps` records what it removes ([withRemovalsRecorded]), a queued snapshot is rebased onto the
 * list in force ([rebasedOnto]) instead of replacing it, and a queued row of the same kind is
 * dropped when a newer edit of that kind lands. Without them the fake would reproduce the bug the
 * real repository no longer has.
 *
 * The in-memory write handles every [SettingsChange] subtype, because the repository is also the
 * fixture for the classification tests.
 *
 * @param initial the settings in force.
 * @param gate the gate every write goes through.
 * @param pendingChanges where a deferred write is stored.
 * @param clock the wall time each write is judged at.
 */
class GatedSettingsRepository(
    initial: GuardConfig,
    private val gate: ChangeGate,
    private val pendingChanges: PendingChangesRepository,
    private val clock: Clock
) : SettingsRepository {

    private val mutableConfig = MutableStateFlow(initial)

    override val config: StateFlow<GuardConfig> = mutableConfig.asStateFlow()

    override val prefs: Flow<UiPrefs> = MutableStateFlow(DefaultTestPrefs)

    /** The edits that took effect, in order. */
    val applied: MutableList<SettingsChange> = mutableListOf()

    /** When set, the next [apply] throws it, so a test can model a failed write. */
    var failure: RuntimeException? = null

    override suspend fun apply(change: SettingsChange): ApplyResult {
        failure?.let { thrown -> throw thrown }
        val nowWall = clock.now().wallMs
        return when (val decision = gate.gate(mutableConfig.value, change, nowWall)) {
            GateDecision.ApplyNow -> {
                applied += change
                mutableConfig.value = applyNow(mutableConfig.value, change)
                dropSuperseded(change, nowWall)
                ApplyResult.Applied
            }

            is GateDecision.Defer -> {
                pendingChanges.add(
                    queuedRowFor(change, mutableConfig.value.apps),
                    decision.applyAtWall
                )
                ApplyResult.Scheduled(decision.applyAtWall)
            }
        }
    }

    /**
     * The row to queue for a deferred edit: a `SetApps` snapshot keeps its list and gains the
     * packages it drops, as `SettingsRepositoryImpl.queuedRowFor` does.
     */
    private fun queuedRowFor(change: SettingsChange, guardedNow: List<GuardedApp>): SettingsChange =
        when (change) {
            is SettingsChange.SetApps -> change.withRemovalsRecorded(guardedNow)
            else -> change
        }

    /**
     * Mirror of `SettingsRepositoryImpl.dropSuperseded`: a row still waiting behind an edit of the
     * same kind that has just landed is dead weight, and would otherwise undo that edit when its own
     * moment came.
     */
    private suspend fun dropSuperseded(applied: SettingsChange, nowWall: Long) {
        for (row in pendingChanges.due(Long.MAX_VALUE)) {
            if (row.applyAt > nowWall && row.change.isSupersededBy(applied)) {
                pendingChanges.remove(row.id)
            }
        }
    }

    override suspend fun updatePrefs(transform: (UiPrefs) -> UiPrefs) = Unit

    override suspend fun bumpOverlayFallback(logicalDay: Int): Int = 1

    /** One change against one config, with no gate and no persistence. */
    private fun applyNow(config: GuardConfig, change: SettingsChange): GuardConfig = when (change) {
        is SettingsChange.SetMode -> config.copy(mode = change.mode)
        // A live edit replaces the list; a queued snapshot is rebased onto the list in force, exactly
        // as `SettingsRepositoryImpl.replaceApps` does (AUDIT-4 A4-12).
        is SettingsChange.SetApps -> config.copy(
            apps = if (change.removed == null) change.apps else change.rebasedOnto(config.apps)
        )
        is SettingsChange.SetSurfaceMode -> config.copy(
            apps = config.apps.map { app ->
                if (app.packageName == change.packageName) app.copy(surfaceMode = change.mode) else app
            }
        )
        is SettingsChange.SetDomains -> config.copy(domains = change.domains)
        is SettingsChange.SetSleepGate -> config.copy(sleepGate = change.cfg)
        is SettingsChange.SetMorning -> config.copy(morning = change.cfg)
        is SettingsChange.SetPrayer -> config.copy(prayer = change.cfg)
        is SettingsChange.SetRamadan -> config.copy(ramadan = change.cfg)
        is SettingsChange.SetStrict -> config.copy(strict = change.cfg)
        is SettingsChange.SetFlags -> config.copy(
            reflectionEnabled = change.reflection ?: config.reflectionEnabled,
            gymHintEnabled = change.gymHint ?: config.gymHintEnabled,
            webGuardEnabled = change.webGuard ?: config.webGuardEnabled
        )
    }
}

/**
 * The pending queue in memory.
 *
 * Ids are assigned like Room's autoincrement, and [due] filters forward-looking rows, so a test can
 * place itself before or after a change's moment by moving [com.ramish.sadiq.testing.FakeClock].
 *
 * @param initial rows to start with.
 */
class FakePendingChangesRepository(
    initial: List<PendingChange> = emptyList()
) : PendingChangesRepository {

    private val rows = MutableStateFlow(initial)

    /** Every queued row, as the UI would see it. */
    val all: List<PendingChange> get() = rows.value

    override fun observe(): Flow<List<PendingChange>> = rows.asStateFlow()

    override suspend fun add(change: SettingsChange, applyAt: Long) {
        val id = (rows.value.maxOfOrNull { row -> row.id } ?: 0L) + 1L
        rows.value = rows.value + PendingChange(
            id = id,
            createdAt = 0L,
            applyAt = applyAt,
            change = change
        )
    }

    override suspend fun due(nowWall: Long): List<PendingChange> =
        rows.value.filter { row -> row.applyAt <= nowWall }.sortedBy { row -> row.applyAt }

    override suspend fun remove(id: Long) {
        rows.value = rows.value.filterNot { row -> row.id == id }
    }
}
