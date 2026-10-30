package com.ramish.sadiq.ui.sky

import androidx.annotation.StringRes
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.GuardController
import com.ramish.sadiq.contract.PermissionSnapshot
import com.ramish.sadiq.core.model.GuardConfig
import com.ramish.sadiq.core.model.Mode
import com.ramish.sadiq.core.model.WindowKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

data class SkyStatus(
    @StringRes val stringRes: Int,
    val formattedArgs: List<Any> = emptyList(),
    val priority: Int,
)

@Singleton
class SkyStatusResolver @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) {
    /**
     * Resolves the highest-priority status message for the Sky screen.
     * Order of priority (highest first):
     * 1. Sadiq is asleep (accessibility off)
     * 2. Sadiq is on but not running (enabled, not bound) — A5-07
     * 3. Focusing (focus running)
     * 4. Bedtime (sleep gate active)
     * 5. Prayer time (prayer window active)
     * 6. Feeds are resting (cooldown active)
     * 7. Your morning (morning window active)
     * 8. Default (guarding N apps, mode)
     */
    fun resolve(
        config: GuardConfig,
        nowWall: Long,
        nextDeadlineWall: Long?,
        windowKind: WindowKind,
        permissionSnapshot: PermissionSnapshot,
        focusRunning: Boolean,
        focusPlannedMs: Long,
        focusStartedWall: Long,
        windowEndWall: Long?,
        prayerName: String?,
    ): SkyStatus {
        if (!permissionSnapshot.accessibilityEnabled) {
            return SkyStatus(R.string.sky_status_asleep, priority = 100)
        }

        // The switch reads on and nothing is bound: the service is gone (killed overnight, unbound by
        // an update), so no window below it can be enforced. Saying "focusing" or "Bedtime" here
        // would describe a guard that is not there (A5-07).
        if (!permissionSnapshot.accessibilityConnected) {
            return SkyStatus(R.string.sky_status_not_running, priority = 95)
        }

        if (focusRunning) {
            val focusEndsAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(focusStartedWall + focusPlannedMs))
            return SkyStatus(R.string.sky_status_focusing, listOf(focusEndsAt), priority = 90)
        }

        when (windowKind) {
            WindowKind.SLEEP_GATE -> {
                val fajrTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(windowEndWall!!))
                return SkyStatus(R.string.sky_status_bedtime, listOf(fajrTime), priority = 80)
            }
            WindowKind.PRAYER -> {
                return SkyStatus(R.string.sky_status_prayer, listOf(prayerName!!), priority = 70)
            }
            WindowKind.MORNING -> {
                val morningEndsAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(windowEndWall!!))
                return SkyStatus(R.string.sky_status_morning, listOf(morningEndsAt), priority = 50)
            }
            WindowKind.NONE -> {
                // A rest is not a window: it runs while the clock sits between windows, and the
                // engine reports it as a wall deadline instead.
                if (nextDeadlineWall != null) {
                    val restEndsAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nextDeadlineWall))
                    return SkyStatus(R.string.sky_status_rest, listOf(restEndsAt), priority = 60)
                }
                // Default status
                val guardedAppCount = config.apps.size
                val modeName = when (config.mode) {
                    Mode.GENTLE -> context.getString(R.string.sky_mode_gentle)
                    Mode.BALANCED -> context.getString(R.string.sky_mode_steady)
                    Mode.HARDCORE -> context.getString(R.string.sky_mode_firm)
                }
                return SkyStatus(R.string.sky_status_default, listOf(guardedAppCount, modeName), priority = 10)
            }
        }
    }
}
