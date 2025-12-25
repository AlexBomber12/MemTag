package com.alexbomber12.memtag.data.queue

import com.alexbomber12.memtag.domain.queue.QueueItem
import com.alexbomber12.memtag.domain.queue.QueueItemStatus
import com.alexbomber12.memtag.domain.queue.QueueMeta
import kotlinx.coroutines.flow.Flow

data class QueueInsertResult(
    val insertedCount: Int,
    val ignoredCount: Int,
)

interface QueueRepository {
    fun observeItems(): Flow<List<QueueItem>>

    fun observeMeta(): Flow<QueueMeta?>

    suspend fun insertItems(
        epcs: List<String>,
        now: Long,
    ): QueueInsertResult

    suspend fun updateStatus(
        epcNormalized: String,
        status: QueueItemStatus,
        updatedAt: Long,
    ): Boolean

    suspend fun setCurrentEpc(epcNormalized: String?)

    suspend fun setLastImportAt(epochMs: Long)

    suspend fun setLastExportAt(epochMs: Long)

    suspend fun clearAll()

    suspend fun getAll(): List<QueueItem>
}
