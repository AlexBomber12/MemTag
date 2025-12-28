package com.alexbomber12.memtag.data.batch

import androidx.room.withTransaction
import com.alexbomber12.memtag.db.BatchDao
import com.alexbomber12.memtag.db.BatchItemEntity
import com.alexbomber12.memtag.db.BatchMetaDao
import com.alexbomber12.memtag.db.BatchMetaEntity
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchItem
import com.alexbomber12.memtag.domain.batch.BatchMeta
import com.alexbomber12.memtag.domain.batch.BatchSessionEntry
import com.alexbomber12.memtag.domain.batch.BatchSource
import com.alexbomber12.memtag.domain.batch.BatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultBatchRepository(
    private val database: MemTagDatabase,
    private val batchDao: BatchDao,
    private val batchMetaDao: BatchMetaDao,
) : BatchRepository {
    override fun observeItems(): Flow<List<BatchItem>> {
        return batchDao.getAllFlow().map { items -> items.map { it.toDomain() } }
    }

    override fun observeMeta(): Flow<BatchMeta?> {
        return batchMetaDao.observeMeta().map { it?.toDomain() }
    }

    override suspend fun insertItems(
        items: List<BatchInputItem>,
        now: Long,
    ): BatchInsertResult {
        if (items.isEmpty()) {
            return BatchInsertResult(insertedCount = 0, ignoredCount = 0)
        }
        val entities =
            items.mapIndexed { index, item ->
                val timestamp = now + index
                BatchItemEntity(
                    epcNormalized = item.epcNormalized,
                    name = item.name.ifBlank { null },
                    status = BatchStatus.UNKNOWN.name,
                    createdAt = timestamp,
                    updatedAt = 0L,
                    note = null,
                    lastProximity = null,
                    lastSeenAt = null,
                    source = null,
                )
            }
        val rowIds = batchDao.insertAll(entities)
        val insertedCount = rowIds.count { it != -1L }
        return BatchInsertResult(
            insertedCount = insertedCount,
            ignoredCount = entities.size - insertedCount,
        )
    }

    override suspend fun updateSession(
        epcNormalized: String,
        status: BatchStatus,
        updatedAt: Long,
        lastSeenAt: Long?,
        lastRssi: Int?,
        source: BatchSource?,
    ): Boolean {
        val updated =
            batchDao.updateSession(
                epcNormalized = epcNormalized,
                status = status.name,
                updatedAt = updatedAt,
                lastSeenAt = lastSeenAt,
                lastRssi = lastRssi,
                source = source?.name,
            )
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
            batchDao.clearAll()
            updateMeta { existing -> existing.copy(currentEpcNormalized = null) }
        }
    }

    override suspend fun getAll(): List<BatchItem> {
        return batchDao.getAll().map { it.toDomain() }
    }

    private suspend fun updateMeta(transform: (BatchMetaEntity) -> BatchMetaEntity) {
        val existing = batchMetaDao.getMeta() ?: BatchMetaEntity()
        batchMetaDao.upsert(transform(existing))
    }

    private fun BatchItemEntity.toDomain(): BatchItem {
        val input =
            BatchInputItem(
                epcNormalized = epcNormalized,
                name = name.orEmpty(),
            )
        val session =
            BatchSessionEntry(
                status = mapStatus(status),
                lastSeenAt = lastSeenAt,
                lastRssi = lastProximity,
                source = mapSource(source),
                updatedAt = updatedAt.takeIf { it > 0 },
            )
        return BatchItem(
            id = id,
            input = input,
            session = session,
            createdAt = createdAt,
        )
    }

    private fun BatchMetaEntity.toDomain(): BatchMeta {
        return BatchMeta(
            currentEpcNormalized = currentEpcNormalized,
            lastImportAt = lastImportAt,
            lastExportAt = lastExportAt,
        )
    }

    private fun mapStatus(raw: String): BatchStatus {
        return when (raw.uppercase()) {
            BatchStatus.UNKNOWN.name -> BatchStatus.UNKNOWN
            BatchStatus.FOUND.name -> BatchStatus.FOUND
            BatchStatus.NOT_FOUND.name -> BatchStatus.NOT_FOUND
            BatchStatus.EXTRA.name -> BatchStatus.EXTRA
            "PENDING" -> BatchStatus.UNKNOWN
            "PRESENT", "FOUND" -> BatchStatus.FOUND
            "MISSING", "SKIPPED", "NOT_FOUND" -> BatchStatus.NOT_FOUND
            else -> BatchStatus.UNKNOWN
        }
    }

    private fun mapSource(raw: String?): BatchSource? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { BatchSource.valueOf(raw) }.getOrNull()
    }
}
