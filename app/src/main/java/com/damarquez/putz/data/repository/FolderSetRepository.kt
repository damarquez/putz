package com.damarquez.putz.data.repository

import com.damarquez.putz.data.local.FolderSetDao
import com.damarquez.putz.data.local.FolderSetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderSetRepository @Inject constructor(
    private val dao: FolderSetDao,
) {
    fun observeIds(): Flow<Set<Long>> = dao.observeAll().map { entities -> entities.mapTo(mutableSetOf()) { it.putioFileId } }

    suspend fun getIdsSnapshot(): Set<Long> = dao.getAll().mapTo(mutableSetOf()) { it.putioFileId }

    suspend fun add(id: Long) = dao.add(FolderSetEntity(putioFileId = id))

    suspend fun remove(id: Long) = dao.remove(id)

    suspend fun clear() = dao.clear()

    // Insert conflicts are IGNOREd at the DAO level, so ids already in the set are left
    // untouched rather than duplicated or reordered.
    suspend fun addAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dao.addAll(ids.map { FolderSetEntity(putioFileId = it) })
    }

    // One DELETE per id rather than a single "WHERE id IN (:ids)" — a bulk selection large
    // enough to blow past SQLite's default ~999 bound-variable limit would otherwise fail
    // outright instead of just being slower.
    suspend fun removeAll(ids: Collection<Long>) {
        ids.forEach { dao.remove(it) }
    }
}
