package com.ramish.sadiq.data.repo

import com.ramish.sadiq.contract.StarRepository
import com.ramish.sadiq.core.economy.StarAward
import com.ramish.sadiq.core.sky.SkyStar
import com.ramish.sadiq.data.db.StarDao
import com.ramish.sadiq.data.db.toEntity
import com.ramish.sadiq.data.db.toSkyStar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sky, backed by the append-only `stars` table.
 *
 * A star is never updated and never un-lit by this repository: only "Delete all history" removes
 * rows, because a sky that shrinks would punish him for looking back (§3.8).
 */
@Singleton
class StarRepositoryImpl @Inject constructor(
    private val starDao: StarDao,
) : StarRepository {

    override fun observeSky(): Flow<List<SkyStar>> =
        starDao.observeAll().map { rows -> rows.map { it.toSkyStar() } }

    override suspend fun insert(award: StarAward) {
        starDao.insert(award.toEntity())
    }

    override suspend fun countLit(): Int = starDao.count()

    override suspend fun deleteAll() {
        starDao.deleteAll()
    }
}
