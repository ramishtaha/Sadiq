package com.ramish.sadiq.data.db

import androidx.room.TypeConverter
import com.ramish.sadiq.core.economy.StarKind
import com.ramish.sadiq.core.engine.GrantEnd
import com.ramish.sadiq.core.engine.GrantKind
import com.ramish.sadiq.core.engine.OpenOutcome
import com.ramish.sadiq.core.engine.SurfaceKind
import com.ramish.sadiq.core.insight.ReflectionReason
import com.ramish.sadiq.core.model.SurfaceMode
import com.ramish.sadiq.core.model.WindowKind
import com.ramish.sadiq.core.sound.SoundKind

/**
 * Enum ↔ TEXT converters for the columns §3.4 declares as `TEXT (<Enum>)`.
 *
 * Enum names are the stored form, never ordinals: a reordered enum must not silently rewrite
 * history, and a human reading the database (or a migration) must see what a row means.
 *
 * Each enum needs its own pair of methods — Room resolves converters by the (parameter, return)
 * type pair, and Kotlin forbids overloads that differ only in return type.
 */
class Converters {

    @TypeConverter
    fun starKindToString(value: StarKind): String = value.name

    @TypeConverter
    fun stringToStarKind(value: String): StarKind = StarKind.valueOf(value)

    @TypeConverter
    fun windowKindToString(value: WindowKind): String = value.name

    @TypeConverter
    fun stringToWindowKind(value: String): WindowKind = WindowKind.valueOf(value)

    @TypeConverter
    fun surfaceKindToString(value: SurfaceKind): String = value.name

    @TypeConverter
    fun stringToSurfaceKind(value: String): SurfaceKind = SurfaceKind.valueOf(value)

    @TypeConverter
    fun openOutcomeToString(value: OpenOutcome): String = value.name

    @TypeConverter
    fun stringToOpenOutcome(value: String): OpenOutcome = OpenOutcome.valueOf(value)

    @TypeConverter
    fun grantKindToString(value: GrantKind): String = value.name

    @TypeConverter
    fun stringToGrantKind(value: String): GrantKind = GrantKind.valueOf(value)

    @TypeConverter
    fun grantEndToString(value: GrantEnd): String = value.name

    @TypeConverter
    fun stringToGrantEnd(value: String): GrantEnd = GrantEnd.valueOf(value)

    @TypeConverter
    fun focusOutcomeToString(value: FocusOutcome): String = value.name

    @TypeConverter
    fun stringToFocusOutcome(value: String): FocusOutcome = FocusOutcome.valueOf(value)

    @TypeConverter
    fun soundKindToString(value: SoundKind): String = value.name

    @TypeConverter
    fun stringToSoundKind(value: String): SoundKind = SoundKind.valueOf(value)

    @TypeConverter
    fun reflectionReasonToString(value: ReflectionReason): String = value.name

    @TypeConverter
    fun stringToReflectionReason(value: String): ReflectionReason = ReflectionReason.valueOf(value)

    @TypeConverter
    fun surfaceModeToString(value: SurfaceMode): String = value.name

    @TypeConverter
    fun stringToSurfaceMode(value: String): SurfaceMode = SurfaceMode.valueOf(value)
}
