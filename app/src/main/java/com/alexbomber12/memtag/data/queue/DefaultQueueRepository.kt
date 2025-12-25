package com.alexbomber12.memtag.data.queue

import androidx.room.withTransaction
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.db.QueueDao
import com.alexbomber12.memtag.db.QueueItemEntity
import com.alexbomber12.memtag.db.QueueMetaDao
import com.alexbomber12.memtag.db.QueueMetaEntity
import com.alexbomber12.memtag.domain.queue.QueueItem
import com.alexbomber12.memtag.domain.queue.QueueItemStatus
import com.alexbomber12.memtag.domain.queue.QueueMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultQueueRepository(
    private val database: MemTagDatabase,
    private val queueDao: QueueDao,
    private val queueMetaDao: QueueMetaDao,
) : QueueRepository {
    override fun observeItems(): Flow<List<QueueItem>> {
        return queueDao.getAllFlow().map { items -> items.map { it.toDomain() } }
    }

    override fun observeMeta(): Flow<QueueMeta?> {
        return queueMetaDao.observeMeta().map { it?.toDomain() }
    }

    override suspend fun insertItems(
        epcs: List<String>,
        now: Long,
    ): QueueInsertResult {
        if (epcs.isEmpty()) {
            return QueueInsertResult(insertedCount = 0, ignoredCount = 0)
        }
        val entities =
            epcs.mapIndexed { index, epc ->
                val timestamp = now + index
                QueueItemEntity(
                    epcNormalized = epc,
                    status = QueueItemStatus.PENDING.name,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    note = null,
                    lastProximity = null,
                )
            }
        val rowIds = queueDao.insertAll(entities)
        val insertedCount = rowIds.count { it != -1L }
        return QueueInsertResult(
            insertedCount = insertedCount,
            ignoredCount = entities.size - insertedCount,
        )
    }

    override suspend fun updateStatus(
        epcNormalized: String,
        status: QueueItemStatus,
        updatedAt: Long,
    ): Boolean {
        val updated = queueDao.updateStatus(epcNormalized, status.name, updatedAt)
        return updated > 0
    }

    override suspend fun setCurrentEpc(epcNormalized: String?) {
        updateMeta { existing ->
            existing.copy(currentEpcNormalized = epcNormalized)
        }
    }

    override suspend fun setLastImportAt(epochMs: Long) {
        updateMeta { existing ->
            existing.copy(lastImportAt = epochMs)
        }
    }

    override suspend fun setLastExportAt(epochMs: Long) {
        updateMeta { existing ->
            existing.copy(lastExportAt = epochMs)
        }
    }

    override suspend fun clearAll() {
        database.withTransaction {
            queueDao.clearAll()
            updateMeta { existing -> existing.copy(currentEpcNormalized = null) }
        }
    }

    override suspend fun getAll(): List<QueueItem> {
        return queueDao.getAll().map { it.toDomain() }
    }

    private suspend fun updateMeta(transform: (QueueMetaEntity) -> QueueMetaEntity) {
        val existing = queueMetaDao.getMeta() ?: QueueMetaEntity()
        queueMetaDao.upsert(transform(existing))
    }

    private fun QueueItemEntity.toDomain(): QueueItem {
        return QueueItem(
            id = id,
            epcNormalized = epcNormalized,
            status = QueueItemStatus.valueOf(status),
            createdAt = createdAt,
            updatedAt = updatedAt,
            note = note,
            lastProximity = lastProximity,
        )
    }

    private fun QueueMetaEntity.toDomain(): QueueMeta {
        return QueueMeta(
            currentEpcNormalized = currentEpcNormalized,
            lastImportAt = lastImportAt,
            lastExportAt = lastExportAt,
        )
    }
}
