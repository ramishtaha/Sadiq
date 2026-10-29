package com.ramish.sadiq.core.testing

import com.ramish.sadiq.core.time.TimeSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds [TimeSnapshot]s from readable local dates, and moves them forward without a real clock.
 *
 * `elapsedMs` is derived from the same instant as `wallMs` (offset by a fixed epoch), so in a test
 * that does not simulate a reboot, one minute of wall time is also one minute of elapsed time — which
 * is what the accounting rules assume. [reboot] is the only way to break that link.
 */
object TestTime {
    /** The zone every test uses unless it says otherwise. */
    const val THANE_ZONE: String = "Asia/Kolkata"

    private const val MINUTE_MS: Long = 60_000L

    /** Elapsed time is "milliseconds since midnight, 1 January 2026" in the snapshot's own zone. */
    private val ELAPSED_EPOCH: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)

    /**
     * A snapshot at a local date-time.
     *
     * @param localIso `yyyy-MM-ddTHH:mm` or `yyyy-MM-ddTHH:mm:ss`, e.g. `"2026-10-29T23:40"`.
     * @param zoneId the IANA zone id; Thane by default.
     * @param bootCount the boot the snapshot belongs to.
     */
    fun at(localIso: String, zoneId: String = THANE_ZONE, bootCount: Int = 1): TimeSnapshot {
        val zone = ZoneId.of(zoneId)
        val local = LocalDateTime.parse(localIso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val wall = local.atZone(zone).toInstant().toEpochMilli()
        val epoch = ELAPSED_EPOCH.atZone(zone).toInstant().toEpochMilli()
        return TimeSnapshot(
            wallMs = wall,
            elapsedMs = wall - epoch,
            bootCount = bootCount,
            zoneId = zoneId
        )
    }

    /**
     * The same snapshot moved forward.
     *
     * @param now the snapshot to move.
     * @param wallMinutes minutes of wall time to add.
     * @param elapsedMinutes minutes of elapsed time to add; equals [wallMinutes] by default.
     */
    fun after(
        now: TimeSnapshot,
        wallMinutes: Long = 0L,
        elapsedMinutes: Long = wallMinutes
    ): TimeSnapshot = now.copy(
        wallMs = now.wallMs + wallMinutes * MINUTE_MS,
        elapsedMs = now.elapsedMs + elapsedMinutes * MINUTE_MS
    )

    /**
     * The same wall time, after a reboot: the boot counter moves and elapsed time restarts.
     *
     * @param now the snapshot to reboot from.
     * @param wallMinutes minutes of wall time that passed while the phone was off.
     * @param elapsedAtBootMs elapsed reading just after boot, one minute by default.
     */
    fun reboot(
        now: TimeSnapshot,
        wallMinutes: Long = 0L,
        elapsedAtBootMs: Long = MINUTE_MS
    ): TimeSnapshot = now.copy(
        wallMs = now.wallMs + wallMinutes * MINUTE_MS,
        elapsedMs = elapsedAtBootMs,
        bootCount = now.bootCount + 1
    )

    /** The snapshot as a local date-time, for asserting on hours and minutes. */
    fun local(now: TimeSnapshot): LocalDateTime =
        Instant.ofEpochMilli(now.wallMs).atZone(ZoneId.of(now.zoneId)).toLocalDateTime()

    /** Minutes since local midnight, 0…1439. */
    fun minuteOfDay(now: TimeSnapshot): Int = local(now).let { it.hour * 60 + it.minute }
}
