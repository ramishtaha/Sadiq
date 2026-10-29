/*
 * Persistence shapes for `sadiq.db` (schema v1, §3.4).
 *
 * Every column carries an explicit @ColumnInfo: the column names are the contract with the plan's
 * reports and with future migrations, so renaming a Kotlin property must never rename a column.
 * All M1 and M2 tables exist from version 1 so the second milestone needs no migration.
 *
 * This file also owns the row-to-contract mapping, because the two must change together.
 */
package com.ramish.sadiq.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.GrantEnd
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.RecordEvent
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.insight.UsageDayRow
import com.ramish.sadiq.core.model.GuardedApp
import com.ramish.sadiq.core.model.SurfaceMode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.core.sound.SoundKind

/** `stars` — one row per lit star. `id` order is the sky order; rows are never updated. */
@Entity(tableName = "stars", indices = [Index(value = ["logical_day"])])
data class StarEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "kind") val kind: StarKind,
    @ColumnInfo(name = "lit_at") val litAt: Long,
    @ColumnInfo(name = "logical_day") val logicalDay: Int,
    @ColumnInfo(name = "window") val window: WindowKind,
    @ColumnInfo(name = "source_package") val sourcePackage: String?,
    @ColumnInfo(name = "source_ref") val sourceRef: String?,
)

/** `open_events` — every guarded open and what he chose, including the decision latency. */
@Entity(
    tableName = "open_events",
    indices = [Index(value = ["logical_day"]), Index(value = ["package_name", "at"])],
)
data class OpenEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "logical_day") val logicalDay: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "domain") val domain: String?,
    @ColumnInfo(name = "surface") val surface: SurfaceKind,
    @ColumnInfo(name = "outcome") val outcome: OpenOutcome,
    @ColumnInfo(name = "decision_ms") val decisionMs: Long?,
    @ColumnInfo(name = "grant_id") val grantId: String?,
)

/** `grants` — one row per closed grant. Open grants live only in the engine snapshot. */
@Entity(tableName = "grants", indices = [Index(value = ["logical_day"])])
data class GrantEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kind") val kind: GrantKind,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "granted_ms") val grantedMs: Long,
    @ColumnInfo(name = "consumed_ms") val consumedMs: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "end_reason") val endReason: GrantEnd,
    @ColumnInfo(name = "first_package") val firstPackage: String,
    @ColumnInfo(name = "window") val window: WindowKind,
    @ColumnInfo(name = "logical_day") val logicalDay: Int,
)

/** How a focus session ended. Persistence-only: [RecordEvent.FocusClosed] carries `completed`. */
enum class FocusOutcome { COMPLETED, ENDED_EARLY }

/** `focus_sessions` — one row per finished focus block, with the stars it actually paid. */
@Entity(tableName = "focus_sessions", indices = [Index(value = ["logical_day"])])
data class FocusSessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "planned_ms") val plannedMs: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "completed_ms") val completedMs: Long,
    @ColumnInfo(name = "outcome") val outcome: FocusOutcome,
    @ColumnInfo(name = "sound") val sound: SoundKind,
    @ColumnInfo(name = "stars") val stars: Int,
    @ColumnInfo(name = "logical_day") val logicalDay: Int,
)

/** `reflections` — what he said when the reflect surface appeared. */
@Entity(tableName = "reflections")
data class ReflectionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "logical_day") val logicalDay: Int,
    @ColumnInfo(name = "reason") val reason: ReflectionReason,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "opens_in_window") val opensInWindow: Int,
)

/**
 * `usage_days` — per-day per-app totals reconciled from UsageStats (M2). The primary key is the
 * `(logical_day, package_name)` pair, so re-reconciling a day replaces a row instead of adding one.
 */
@Entity(tableName = "usage_days", primaryKeys = ["logical_day", "package_name"])
data class UsageDayEntity(
    @ColumnInfo(name = "logical_day") val logicalDay: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "foreground_ms") val foregroundMs: Long,
    @ColumnInfo(name = "opens") val opens: Int,
    @ColumnInfo(name = "night_ms") val nightMs: Long,
)

/** `guarded_apps` — the guard list. This is config, not history: "Delete all history" keeps it. */
@Entity(tableName = "guarded_apps")
data class GuardedAppEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "surface_mode") val surfaceMode: SurfaceMode,
)

/** `guarded_domains` — M2 web guard list; the schema exists in M1 so M2 needs no migration. */
@Entity(tableName = "guarded_domains")
data class GuardedDomainEntity(
    @PrimaryKey @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** `pending_changes` — a loosening change deferred by strict mode, stored as serialized JSON. */
@Entity(tableName = "pending_changes")
data class PendingChangeEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "apply_at") val applyAt: Long,
    @ColumnInfo(name = "change_json") val changeJson: String,
)

// ── Row → contract ───────────────────────────────────────────────────────────────────────────────

internal fun StarEntity.toStarAward(): StarAward = StarAward(
    kind = kind,
    litWall = litAt,
    logicalDay = logicalDay,
    window = window,
    sourcePackage = sourcePackage,
    sourceRef = sourceRef,
)

/** The sky only needs the display projection; `id` is the sky order (§3.4). */
internal fun StarEntity.toSkyStar(): SkyStar = SkyStar(
    index = id.toInt(),
    kind = kind,
    litWall = litAt,
    logicalDay = logicalDay,
    sourcePackage = sourcePackage,
)

internal fun OpenEventEntity.toRecordOpen(): RecordEvent.Open = RecordEvent.Open(
    at = at,
    logicalDay = logicalDay,
    packageName = packageName,
    domain = domain,
    surface = surface,
    outcome = outcome,
    decisionMs = decisionMs,
    grantId = grantId,
)

internal fun ReflectionEntity.toRecordReflected(): RecordEvent.Reflected = RecordEvent.Reflected(
    at = at,
    logicalDay = logicalDay,
    reason = reason,
    packageName = packageName,
    opensInWindow = opensInWindow,
)

internal fun UsageDayEntity.toUsageDayRow(): UsageDayRow = UsageDayRow(
    logicalDay = logicalDay,
    packageName = packageName,
    foregroundMs = foregroundMs,
    opens = opens,
    nightMs = nightMs,
)

internal fun GuardedAppEntity.toGuardedApp(): GuardedApp = GuardedApp(
    packageName = packageName,
    label = label,
    surfaceMode = surfaceMode,
)

// ── contract → row ───────────────────────────────────────────────────────────────────────────────

internal fun StarAward.toEntity(): StarEntity = StarEntity(
    kind = kind,
    litAt = litWall,
    logicalDay = logicalDay,
    window = window,
    sourcePackage = sourcePackage,
    sourceRef = sourceRef,
)

internal fun RecordEvent.Open.toEntity(): OpenEventEntity = OpenEventEntity(
    at = at,
    logicalDay = logicalDay,
    packageName = packageName,
    domain = domain,
    surface = surface,
    outcome = outcome,
    decisionMs = decisionMs,
    grantId = grantId,
)

internal fun RecordEvent.GrantClosed.toEntity(): GrantEntity = GrantEntity(
    id = grant.id,
    kind = grant.kind,
    startedAt = grant.startedWall,
    grantedMs = grant.grantedMs,
    consumedMs = grant.consumedMs,
    endedAt = endedAt,
    endReason = end,
    firstPackage = grant.firstPackage,
    window = grant.windowAtStart,
    logicalDay = logicalDay,
)

internal fun RecordEvent.StarLit.toEntity(): StarEntity = award.toEntity()

internal fun RecordEvent.FocusClosed.toEntity(): FocusSessionEntity = FocusSessionEntity(
    id = focus.id,
    startedAt = focus.startedWall,
    plannedMs = focus.plannedMs,
    endedAt = endedAt,
    completedMs = focus.elapsedMs,
    outcome = if (completed) FocusOutcome.COMPLETED else FocusOutcome.ENDED_EARLY,
    sound = focus.sound,
    stars = stars,
    logicalDay = logicalDay,
)

internal fun RecordEvent.Reflected.toEntity(): ReflectionEntity = ReflectionEntity(
    at = at,
    logicalDay = logicalDay,
    reason = reason,
    packageName = packageName,
    opensInWindow = opensInWindow,
)

internal fun UsageDayRow.toEntity(): UsageDayEntity = UsageDayEntity(
    logicalDay = logicalDay,
    packageName = packageName,
    foregroundMs = foregroundMs,
    opens = opens,
    nightMs = nightMs,
)

internal fun GuardedApp.toEntity(addedAt: Long): GuardedAppEntity = GuardedAppEntity(
    packageName = packageName,
    label = label,
    addedAt = addedAt,
    surfaceMode = surfaceMode,
)
