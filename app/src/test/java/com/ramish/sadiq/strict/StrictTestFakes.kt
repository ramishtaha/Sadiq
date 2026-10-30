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
import com.ramish.sadiq.core.strict.rebasedOnto
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.data.settings.deferredAgainst
import com.ramish.sadiq.data.settings.isMadePointlessBy
import com.ramish.sadiq.data.settings.queuedRemovals
import com.ramish.sadiq.data.settings.rebasedBehind
import com.ramish.sadiq.data.settings.rewritesAfterAppWrite
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
 * The AUDIT-4 A4-12 and AUDIT-5 pieces are not mirrored here by hand: every one of them is the
 * production function, called with this fake's in-memory table instead of Room's.
 * - a deferred `SetApps` records what it removes, against the table and excluding what an earlier
 *   row already drops ([deferredAgainst]);
 * - a queued snapshot is rebased onto the list in force ([rebasedOnto]) instead of replacing it;
 * - every write composes the rows still waiting ([rewritesAfterAppWrite]), so a stale snapshot
 *   cannot re-add what an earlier row removed or un-guard an app he re-added;
 * - a queued row a newer edit has replaced is dropped, field by field ([isMadePointlessBy]).
 *
 * Without them the fake would reproduce the bugs the real repository no longer has, so a test that
 * passed here would prove nothing.
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
        val before = mutableConfig.value
        return when (val decision = gate.gate(before, change, nowWall)) {
            GateDecision.ApplyNow -> {
                applied += change
                val next = applyNow(before, change)
                mutableConfig.value = next
                composeWaitingRows(
                    before = before.apps,
                    written = next.apps,
                    nowWall = nowWall,
                    live = change !is SettingsChange.SetApps || change.removed == null,
                )
                settleWaitingEdits(change, nowWall, before, next.apps)
                ApplyResult.Applied
            }

            is GateDecision.Defer -> {
                pendingChanges.add(
                    queuedRowFor(change, before.apps),
                    decision.applyAtWall
                )
                ApplyResult.Scheduled(decision.applyAtWall)
            }
        }
    }

    /**
     * The row to queue for a deferred edit: a `SetApps` snapshot keeps its list, gains the packages
     * it drops and loses the ones an earlier row is already dropping, as
     * `SettingsRepositoryImpl.queuedRowFor` does.
     */
    private suspend fun queuedRowFor(
        change: SettingsChange,
        guardedNow: List<GuardedApp>
    ): SettingsChange = when (change) {
        is SettingsChange.SetApps -> change.deferredAgainst(
            guardedNow = guardedNow,
            alreadyQueuedRemovals = pendingChanges.due(Long.MAX_VALUE).queuedRemovals(),
        )

        else -> change
    }

    /**
     * Mirror of `SettingsRepositoryImpl.composeWaitingRows`: every row still waiting is brought up to
     * date with what this write left in the table.
     */
    private suspend fun composeWaitingRows(
        before: List<GuardedApp>,
        written: List<GuardedApp>,
        nowWall: Long,
        live: Boolean
    ) {
        val rewrites = pendingChanges.due(Long.MAX_VALUE)
            .rewritesAfterAppWrite(before, written, nowWall, live)
        for (rewrite in rewrites) {
            pendingChanges.remove(rewrite.row.id)
            pendingChanges.add(rewrite.replacement, rewrite.row.applyAt)
        }
    }

    /**
     * Mirror of `SettingsRepositoryImpl.settleWaitingEdits`: a row still waiting behind an edit that
     * has already done its work is dead weight and goes; one that still has a different field to
     * change is re-cut so it carries only that field.
     */
    private suspend fun settleWaitingEdits(
        applied: SettingsChange,
        nowWall: Long,
        before: GuardConfig,
        written: List<GuardedApp>
    ) {
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
