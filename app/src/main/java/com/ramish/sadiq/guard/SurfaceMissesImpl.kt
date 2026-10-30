package com.ramish.sadiq.guard

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SurfaceMisses] over one private preferences file.
 *
 * SharedPreferences rather than the settings DataStore on purpose: this is two integers written
 * from the guard worker at the moment a surface is missed, and the presenter must be able to count a
 * miss without a coroutine or a suspension point — the fallback timer is already inside one, and a
 * DataStore write there would have to be joined to a scope that the service owns, not this class.
 * The file is Sadiq's own and carries no personal data beyond a day number and a count.
 *
 * The flow is a [MutableStateFlow] so the Sky card can re-render the moment a miss is recorded,
 * without the screen polling the file.
 */
@Singleton
class SurfaceMissesImpl @Inject constructor(
    @ApplicationContext context: Context,
) : SurfaceMisses {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val mutableLast = MutableStateFlow(read())

    override val last: Flow<SurfaceMiss?> = mutableLast.asStateFlow()

    /**
     * Counts one miss.
     *
     * Synchronized because two guarded apps can open within the same second: read-modify-write on a
     * plain field would lose one of them, and the count is what decides whether the user has been
     * told.
     */
    @Synchronized
    override fun record(day: Int): Int {
        val current = mutableLast.value
        val next = if (current != null && current.day == day) current.count + 1 else 1
        mutableLast.value = SurfaceMiss(day, next)
        prefs.edit()
            .putInt(KEY_DAY, day)
            .putInt(KEY_COUNT, next)
            .apply()
        return next
    }

    /** The stored record, or null when this install has never missed a question. */
    private fun read(): SurfaceMiss? {
        val day = prefs.getInt(KEY_DAY, NO_DAY)
        if (day == NO_DAY) return null
        val count = prefs.getInt(KEY_COUNT, 0)
        return if (count > 0) SurfaceMiss(day, count) else null
    }

    private companion object {
        /** Its own file: nothing else may write here, so a corrupt value is always ours. */
        const val FILE: String = "surface_misses"

        const val KEY_DAY: String = "day"

        const val KEY_COUNT: String = "count"

        /** `getInt`'s default when no day has ever been written. No epoch day is negative in use. */
        const val NO_DAY: Int = Int.MIN_VALUE
    }
}
