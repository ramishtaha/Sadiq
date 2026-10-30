package com.ramish.sadiq.ui.sky

import android.content.Context
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.contract.HomeCardIcon
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.Tri
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.LogicalDays
import com.ramish.sadiq.guard.SurfaceMiss
import com.ramish.sadiq.guard.SurfaceMisses
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The card that explains a question which never appeared (AUDIT-5 A5-04).
 *
 * With the overlay host off, a launch HyperOS refuses is invisible: the reel pauses and nothing
 * takes its place. [SurfaceMisses] records that, and this card is the Sky's half of the answer — it
 * stays up for the rest of the logical day, or until pop-ups are allowed, and its action opens the
 * pop-ups page rather than a general settings list.
 *
 * It outranks the asleep card (100) on purpose: "Sadiq is asleep" is a state he chose by not turning
 * accessibility on, while this is a live failure that just cost him a paused reel, and only this card
 * carries the fix that failure needs.
 */
@Singleton
class PopupsMissCardProvider @Inject constructor(
    private val misses: SurfaceMisses,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val homeAction: HomeAction,
    private val clock: Clock,
    @ApplicationContext private val context: Context,
) : HomeCardProvider {

    override val priority: Int = 110

    override val card: Flow<HomeCardModel?> =
        combine(misses.last, permissionStatusProvider.changes) { miss, perms -> cardFor(miss, perms) }

    /**
     * The card for one reading, or null when there is nothing true to say.
     *
     * The strings are resolved here rather than in a composable because a [HomeCardModel] carries
     * text, not resources: the Sky screen draws whatever the winning provider hands it and never
     * learns which feature is speaking.
     */
    private fun cardFor(miss: SurfaceMiss?, perms: PermissionSnapshot): HomeCardModel? {
        if (miss == null || miss.count < 1) return null
        // Pop-ups allowed since the miss: the question can appear again and the card would be a
        // complaint about a fixed problem. UNKNOWN does not count as allowed — on HyperOS this op
        // reads UNKNOWN on builds that will not answer, and the recorded miss is the only evidence
        // there is.
        if (perms.backgroundPopups == Tri.YES) return null
        // A service that cannot ask at all has a more urgent card of its own ("asleep", "on but not
        // running"); this one would only hide it. The miss stays recorded and comes back the moment
        // the service is up again, because both readings are in this flow's inputs.
        if (!perms.accessibilityEnabled || !perms.accessibilityConnected) return null
        val now = clock.now()
        if (miss.day != LogicalDays.of(now.wallMs, now.zoneId)) return null
        return HomeCardModel(
            id = CARD_ID,
            icon = HomeCardIcon.WARNING,
            title = context.getString(R.string.card_popups_missed_title),
            body = context.getString(R.string.card_popups_missed_body),
            actionLabel = context.getString(R.string.card_popups_missed_action),
            action = { homeAction.openSettings(SettingsTarget.MIUI_OTHER_PERMISSIONS) }
        )
    }

    private companion object {
        /** Stable identity, so the same card is not animated twice as the day's count climbs. */
        const val CARD_ID: String = "popups_missed"
    }
}
