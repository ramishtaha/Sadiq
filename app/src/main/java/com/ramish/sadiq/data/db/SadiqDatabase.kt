package com.ramish.sadiq.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * `sadiq.db`, schema version 1 (§3.4).
 *
 * All M1 and M2 tables are created here, so the second milestone adds no migration. Any later
 * change needs an explicit [androidx.room.migration.Migration] and a migration test; there is
 * deliberately no `fallbackToDestructiveMigration` anywhere in the build.
 *
 * `exportSchema` is on. Exporting needs the Room schema directory passed to KSP by the app module's
 * build script (`room.schemaLocation` / `room { schemaDirectory(...) }`), which is outside this
 * work package — until that lands, Room can only warn, not write `app/schemas/`.
 */
@Database(
    entities = [
        StarEntity::class,
        OpenEventEntity::class,
        GrantEntity::class,
        FocusSessionEntity::class,
        ReflectionEntity::class,
        UsageDayEntity::class,
        GuardedAppEntity::class,
        GuardedDomainEntity::class,
        PendingChangeEntity::class,
    ],
    version = SadiqDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SadiqDatabase : RoomDatabase() {

    abstract fun starDao(): StarDao

    abstract fun historyDao(): HistoryDao

    abstract fun configDao(): ConfigDao

    abstract fun pendingChangeDao(): PendingChangeDao

    companion object {
        /** File name inside the app's database directory. */
        const val NAME: String = "sadiq.db"

        /** Schema version. Bumping this requires a [androidx.room.migration.Migration]. */
        const val VERSION: Int = 1
    }
}
