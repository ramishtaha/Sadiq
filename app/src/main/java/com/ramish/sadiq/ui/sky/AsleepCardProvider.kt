package com.ramish.sadiq.ui.sky

import android.content.Context
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.HomeCardIcon
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.HomeAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AsleepCardProvider @Inject constructor(
    private val permissionStatusProvider: PermissionStatusProvider,
    private val homeAction: HomeAction,
    @ApplicationContext private val context: Context,
) : HomeCardProvider {
    override val priority: Int = 100

    /**
     * Two faults, two sentences (A5-07).
     *
     * - **Off.** Sadiq cannot ask anything, and the fix is to turn the service on.
     * - **On, not bound.** The switch reads on while HyperOS has killed the process or an update has
     *   unbound the service. Telling him to "wake" a service that is already on would be untrue, and
     *   the fix is in the service's own page, not the permission it already holds.
     *
     * The snapshot's `accessibilityConnected` already carries the cold-start grace window, so the
     * card does not flash for the second or two a real start takes to bind.
     */
    override val card: Flow<HomeCardModel?> = permissionStatusProvider.changes.map { snapshot ->
        when {
            !snapshot.accessibilityEnabled -> HomeCardModel(
                id = "asleep",
                icon = HomeCardIcon.WARNING,
                title = context.getString(R.string.card_asleep_title),
                body = context.getString(R.string.card_asleep_body),
                actionLabel = context.getString(R.string.card_asleep_action),
                action = { homeAction.openSettings(SettingsTarget.ACCESSIBILITY) }
            )

            !snapshot.accessibilityConnected -> HomeCardModel(
                id = "not_running",
                icon = HomeCardIcon.WARNING,
                title = context.getString(R.string.card_not_running_title),
                body = context.getString(R.string.card_not_running_body),
                actionLabel = context.getString(R.string.card_asleep_action),
                action = { homeAction.openSettings(SettingsTarget.ACCESSIBILITY_DETAILS) }
            )

            else -> null
        }
    }
}
