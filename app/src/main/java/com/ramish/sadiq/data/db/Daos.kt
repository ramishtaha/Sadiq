package com.ramish.sadiq.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ramish.sadiq.core.model.SurfaceMode
import kotlinx.coroutines.flow.Flow

/**
 * Queries are grouped by the repository that owns them, and each DAO exposes exactly what its
 * repository needs — no generic "select everything" escape hatch.
 *
 * `window` is a SQLite keyword; no query here names it. Room quotes column names in the statements
 * it generates, so the entity may still use it (§3.4 requires the column name).
 */

/** Reads and inserts for the sky. Stars are append-only; deleting is a separate, explicit act. */
@Dao
interface StarDao {

    @Query("SELECT * FROM stars ORDER BY id ASC")
    fun observeAll(): Flow<List<StarEntity>>

    @Insert
    suspend fun insert(entity: StarEntity): Long

    @Query("SELECT COUNT(*) FROM stars")
    suspend fun count(): Int

    @Query("DELETE FROM stars")
    suspend fun deleteAll()
}

/** Everything the Week screen and the reconciliation path read or write. */
@Dao
interface HistoryDao {

    @Insert
    suspend fun insertOpen(row: OpenEventEntity)

    @Insert
    suspend fun insertGrant(row: GrantEntity)

    @Insert
    suspend fun insertFocus(row: FocusSessionEntity)

    @Insert
    suspend fun insertReflection(row: ReflectionEntity)

    @Upsert
    suspend fun upsertUsageDays(rows: List<UsageDayEntity>)

    @Query("SELECT * FROM open_events WHERE logical_day BETWEEN :fromDay AND :toDay ORDER BY at ASC")
    suspend fun opensBetween(fromDay: Int, toDay: Int): List<OpenEventEntity>

    @Query("SELECT * FROM reflections WHERE logical_day BETWEEN :fromDay AND :toDay ORDER BY at ASC")
    suspend fun reflectionsBetween(fromDay: Int, toDay: Int): List<ReflectionEntity>

    @Query("SELECT * FROM stars WHERE logical_day BETWEEN :fromDay AND :toDay ORDER BY id ASC")
    suspend fun starsBetween(fromDay: Int, toDay: Int): List<StarEntity>

    @Query("SELECT * FROM usage_days WHERE logical_day BETWEEN :fromDay AND :toDay ORDER BY logical_day ASC, package_name ASC")
    suspend fun usageBetween(fromDay: Int, toDay: Int): List<UsageDayEntity>

    @Query("SELECT * FROM grants WHERE logical_day BETWEEN :fromDay AND :toDay ORDER BY started_at ASC")
    suspend fun grantsBetween(fromDay: Int, toDay: Int): List<GrantEntity>

    @Query("SELECT COALESCE(SUM(completed_ms), 0) FROM focus_sessions WHERE logical_day BETWEEN :fromDay AND :toDay")
    suspend fun focusMsBetween(fromDay: Int, toDay: Int): Long

    // The five history tables. Guarded apps, domains and pending changes are config, not history.
    @Query("DELETE FROM open_events")
    suspend fun deleteOpens()

    @Query("DELETE FROM grants")
    suspend fun deleteGrants()

    @Query("DELETE FROM focus_sessions")
    suspend fun deleteFocusSessions()

    @Query("DELETE FROM reflections")
    suspend fun deleteReflections()

    @Query("DELETE FROM usage_days")
    suspend fun deleteUsageDays()
}

/** The two guard tables that the settings repository folds into [com.ramish.sadiq.core.model.GuardConfig]. */
@Dao
interface ConfigDao {

    @Query("SELECT * FROM guarded_apps ORDER BY label COLLATE NOCASE ASC")
    fun observeApps(): Flow<List<GuardedAppEntity>>

    @Query("SELECT * FROM guarded_apps")
    suspend fun appsOnce(): List<GuardedAppEntity>

    @Query("DELETE FROM guarded_apps")
    suspend fun clearApps()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(rows: List<GuardedAppEntity>)

    @Query("UPDATE guarded_apps SET surface_mode = :mode WHERE package_name = :packageName")
    suspend fun setSurfaceMode(packageName: String, mode: SurfaceMode)

    @Query("SELECT * FROM guarded_domains ORDER BY domain ASC")
    fun observeDomains(): Flow<List<GuardedDomainEntity>>

    @Query("SELECT * FROM guarded_domains")
    suspend fun domainsOnce(): List<GuardedDomainEntity>

    @Query("DELETE FROM guarded_domains")
    suspend fun clearDomains()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDomains(rows: List<GuardedDomainEntity>)
}

/** Strict-mode deferrals (M2). Rows are opaque JSON to the database. */
@Dao
interface PendingChangeDao {

    @Query("SELECT * FROM pending_changes ORDER BY apply_at ASC, id ASC")
    fun observeAll(): Flow<List<PendingChangeEntity>>

    @Insert
    suspend fun insert(row: PendingChangeEntity): Long

    @Query("SELECT * FROM pending_changes WHERE apply_at <= :nowWall ORDER BY apply_at ASC, id ASC")
    suspend fun dueBy(nowWall: Long): List<PendingChangeEntity>

    @Query("DELETE FROM pending_changes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
