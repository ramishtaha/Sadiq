package com.ramish.sadiq.guard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.ramish.sadiq.core.time.Clock
import com.ramish.sadiq.core.time.TimeSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one implementation of [Clock] over the platform clocks (§3.3).
 *
 * Two clocks are read on purpose. `System.currentTimeMillis()` is civil time and decides which
 * window and which logical day it is; `SystemClock.elapsedRealtime()` counts awake time and decides
 * how much of a grant, rest or focus session has actually been spent. `Settings.Global.BOOT_COUNT`
 * tells the engine that the second clock restarted, which is the only way it can tell a reboot from
 * a long sleep.
 *
 * The wall reading is shifted by [ClockOffset], which is always zero outside a debug time-travel run.
 * `elapsedRealtime` is never shifted: the whole point of the offset is that it can move the calendar
 * without buying the user extra minutes.
 */
@Singleton
class SystemClockSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : Clock {

    override fun now(): TimeSnapshot = TimeSnapshot(
        wallMs = ClockOffset.applyTo(System.currentTimeMillis()),
        elapsedMs = SystemClock.elapsedRealtime(),
        bootCount = bootCount(),
        zoneId = TimeZone.getDefault().id,
    )

    /**
     * `Settings.Global.BOOT_COUNT` since the device booted.
     *
     * A missing or unreadable counter is reported as zero rather than as null: the engine only
     * compares two readings, so a ROM that hides the setting still gets a consistent answer, and a
     * constant zero simply means "never treated as a reboot".
     */
    private fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    } catch (_: SecurityException) {
        // Some vendor ROMs refuse the read to a plain app; a stable zero keeps accounting honest.
        0
    }
}
