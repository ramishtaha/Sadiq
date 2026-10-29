package com.ramish.sadiq.strict

import com.ramish.sadiq.contract.ChangeGate
import com.ramish.sadiq.contract.GateDecision
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.schedule.RamadanCalendar
import com.ramish.sadiq.core.strict.ChangeClass
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.core.strict.StrictPolicy
import com.ramish.sadiq.core.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The data layer's optional gate, bound only once this feature ships.
 *
 * [com.ramish.sadiq.data.settings.SettingsRepositoryImpl] asks this for every write, so a loosening
 * edit is turned into a pending change in one place instead of in each of the nine screens that can
 * make one. Three things decide the answer:
 *
 * 1. The feature is off → apply at once. Nothing waits unless the user turned the wait on, and a
 *    pending row left over from an older install must never surprise someone who has switched it off.
 * 2. The policy says tightening or neutral → apply at once. Tightening is the direction the user is
 *    trying to move; delaying it would punish the good decision.
 * 3. Otherwise the edit waits `strict.delayMin`, or thirty minutes while Ramadan mode is on.
 *
 * The clock is consulted only for its zone: Ramadan is a Hijri month, and the month turns at local
 * midnight. [nowWall] is the request's own wall time, so the wait is measured from the moment the
 * user tapped, not from whenever the gate ran.
 *
 * **Due changes bypass the gate.** [PendingChangeApplier] re-submits a stored edit through
 * `SettingsRepository.apply`, which asks this gate again; without a bypass the wait would restart
 * forever and nothing would ever land. [applyingDueChange] marks the coroutine, so the same gate
 * answers `ApplyNow` for the one edit that has already served its time.
 */
@Singleton
class ChangeGateImpl @Inject constructor(
    private val policy: StrictPolicy,
    private val clock: Clock,
) : ChangeGate {

    override suspend fun gate(current: GuardConfig, change: SettingsChange, nowWall: Long): GateDecision {
        // A change whose time has come is not re-classified: it was already judged, shown in Guard
        // and waited out. Re-judging it is how a deferral becomes a permanent block.
        if (currentCoroutineContext()[DueChangeBypassKey] != null) return GateDecision.ApplyNow

        if (!current.strict.enabled) return GateDecision.ApplyNow

        val classification = policy.classify(current, change)
        if (classification != ChangeClass.LOOSENING) return GateDecision.ApplyNow

        val ramadanActive = RamadanCalendar.isRamadan(
            wallMs = nowWall,
            zoneId = clock.now().zoneId,
            config = current.ramadan,
        )
        val applyAtWall = policy.applyAtWall(nowWall, classification, current.strict, ramadanActive)
        return if (applyAtWall <= nowWall) GateDecision.ApplyNow else GateDecision.Defer(applyAtWall)
    }

    /**
     * Run [block] with the gate's due-change bypass set.
     *
     * A coroutine-context element rather than a field, because the gate is a singleton shared by
     * every writer: a flag on the object would let one screen's edit slip through while another
     * coroutine is applying a pending one.
     *
     * @param block the work that re-submits a due change.
     */
    suspend fun <T> applyingDueChange(block: suspend () -> T): T =
        withContext(DueChangeBypass()) { block() }

    /**
     * The marker itself. A private key in this file is the whole scope of the bypass, so no other
     * package can set it by accident.
     */
    private object DueChangeBypassKey : CoroutineContext.Key<DueChangeBypass>

    private class DueChangeBypass : AbstractCoroutineContextElement(DueChangeBypassKey)
}
