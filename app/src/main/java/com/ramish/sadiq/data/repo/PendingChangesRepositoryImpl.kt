package com.ramish.sadiq.data.repo

import com.ramish.sadiq.contract.PendingChange
import com.ramish.sadiq.contract.PendingChangesRepository
import com.ramish.sadiq.core.strict.SettingsChange
import com.ramish.sadiq.data.db.PendingChangeDao
import com.ramish.sadiq.data.db.PendingChangeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strict-mode deferrals: a loosening change is stored as JSON until its wall-clock moment arrives.
 *
 * A row whose JSON no longer decodes (an older or a bogus change type) is skipped rather than
 * crashing the Guard tab — the change is simply not offered, and a new one can be made.
 */
@Singleton
class PendingChangesRepositoryImpl @Inject constructor(
    private val pendingChangeDao: PendingChangeDao,
) : PendingChangesRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun observe(): Flow<List<PendingChange>> =
        pendingChangeDao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    override suspend fun add(change: SettingsChange, applyAt: Long) {
        pendingChangeDao.insert(
            PendingChangeEntity(
                createdAt = System.currentTimeMillis(),
                applyAt = applyAt,
                changeJson = json.encodeToString(SettingsChange.serializer(), change),
            ),
        )
    }

    override suspend fun due(nowWall: Long): List<PendingChange> =
        pendingChangeDao.dueBy(nowWall).mapNotNull { it.toModel() }

    override suspend fun remove(id: Long) {
        pendingChangeDao.deleteById(id)
    }

    private fun PendingChangeEntity.toModel(): PendingChange? {
        val decoded = try {
            json.decodeFromString(SettingsChange.serializer(), changeJson)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return PendingChange(id = id, createdAt = createdAt, applyAt = applyAt, change = decoded)
    }
}
