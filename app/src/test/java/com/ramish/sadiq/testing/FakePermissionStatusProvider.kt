package com.ramish.sadiq.testing

import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.Tri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A permission state where nothing has been granted yet, which is what a fresh install sees. */
val NothingGranted: PermissionSnapshot = PermissionSnapshot(
    accessibilityEnabled = false,
    accessibilityConnected = false,
    notifications = false,
    usageAccess = false,
    batteryUnrestricted = false,
    isXiaomiFamily = false,
    hyperOsMajor = null,
    backgroundPopups = Tri.UNKNOWN,
    autostart = Tri.UNKNOWN,
    liveUpdates = Tri.UNKNOWN,
    sdkInt = 34
)

/**
 * A [PermissionStatusProvider] whose snapshot the test sets.
 *
 * `refresh()` counts calls but changes nothing: a test asserts that a screen asked for a refresh on
 * resume, then sets the new truth with [set].
 *
 * @param initial the snapshot before anything is granted.
 */
class FakePermissionStatusProvider(
    initial: PermissionSnapshot = NothingGranted
) : PermissionStatusProvider {

    private val mutableChanges = MutableStateFlow(initial)

    override val changes: StateFlow<PermissionSnapshot> = mutableChanges.asStateFlow()

    /** How many times [refresh] was called. */
    var refreshCount: Int = 0
        private set

    override fun snapshot(): PermissionSnapshot = mutableChanges.value

    override fun refresh() {
        refreshCount += 1
    }

    /** Publish a new snapshot. @param snapshot the new truth. */
    fun set(snapshot: PermissionSnapshot) {
        mutableChanges.value = snapshot
    }

    /** Change one field of the snapshot. @param transform the edit. */
    fun update(transform: (PermissionSnapshot) -> PermissionSnapshot) {
        mutableChanges.value = transform(mutableChanges.value)
    }
}
