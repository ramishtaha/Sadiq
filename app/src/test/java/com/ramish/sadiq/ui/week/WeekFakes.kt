package com.ramish.sadiq.ui.week

import android.graphics.drawable.Drawable
import com.ramish.sadiq.contract.AppInfo
import com.ramish.sadiq.contract.InstalledAppsSource
import com.ramish.sadiq.contract.OemSettingsLauncher
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.UsageHistorySource
import com.ramish.sadiq.core.engine.UsageSpan
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardConfig

/**
 * A [UsageHistorySource] the test fills by hand.
 *
 * @param access whether usage access is granted.
 * @param rows the rows the platform would report, already scoped to guarded apps.
 */
class FakeUsageHistorySource(
    var access: Boolean = true,
    var rows: List<UsageDayRow> = emptyList(),
    var hourly: LongArray = LongArray(24),
) : UsageHistorySource {

    /** The last day range [dailyRows] was asked for, so a test can check the aggregator's window. */
    var lastDays: IntRange? = null
        private set

    /** The last day range [hourlyMs] was asked for. */
    var lastHourlyDays: IntRange? = null
        private set

    override fun hasAccess(): Boolean = access

    override suspend fun spans(fromWall: Long, toWall: Long): List<UsageSpan> = emptyList()

    override suspend fun dailyRows(
        days: IntRange,
        zoneId: String,
        config: GuardConfig,
    ): List<UsageDayRow> {
        lastDays = days
        return rows.filter { row -> row.logicalDay in days }
    }

    override suspend fun hourlyMs(days: IntRange, zoneId: String, packages: Set<String>): LongArray {
        lastHourlyDays = days
        return hourly
    }
}

/** An [InstalledAppsSource] with a fixed list, so labels are known without a package manager. */
class FakeInstalledAppsSource(
    var apps: List<AppInfo> = emptyList(),
) : InstalledAppsSource {

    override suspend fun launchableApps(): List<AppInfo> = apps

    override fun icon(packageName: String): Drawable? = null
}

/** An [OemSettingsLauncher] that records the page it was asked for instead of opening it. */
class FakeOemSettingsLauncher : OemSettingsLauncher {

    /** The last target requested, or null when nothing was opened. */
    var opened: SettingsTarget? = null
        private set

    /** Every target requested, in order. */
    val openedTargets: MutableList<SettingsTarget> = mutableListOf()

    override fun open(target: SettingsTarget): Boolean {
        opened = target
        openedTargets += target
        return true
    }
}
