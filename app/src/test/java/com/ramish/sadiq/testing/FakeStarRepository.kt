package com.ramish.sadiq.testing

import com.ramish.sadiq.contract.StarRepository
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.sky.SkyStar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [StarRepository] held in memory, preserving insertion order: index 0 is the oldest star, which is
 * the sky's drawing order.
 *
 * @param initial the stars already in the sky.
 */
class FakeStarRepository(
    initial: List<StarAward> = emptyList()
) : StarRepository {

    private val awards = MutableStateFlow(initial)

    override fun observeSky(): Flow<List<SkyStar>> = awards.map { list ->
        list.mapIndexed { index, award ->
            SkyStar(
                index = index,
                kind = award.kind,
                litWall = award.litWall,
                logicalDay = award.logicalDay,
                sourcePackage = award.sourcePackage
            )
        }
    }

    override suspend fun insert(award: StarAward) {
        awards.value = awards.value + award
    }

    override suspend fun countLit(): Int = awards.value.size

    override suspend fun deleteAll() {
        awards.value = emptyList()
    }

    /** Every star inserted, oldest first. */
    fun awards(): List<StarAward> = awards.value
}
