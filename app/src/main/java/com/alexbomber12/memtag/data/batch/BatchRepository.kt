package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchItem
import com.alexbomber12.memtag.domain.batch.BatchMeta
import com.alexbomber12.memtag.domain.batch.BatchSource
import com.alexbomber12.memtag.domain.batch.BatchStatus
import kotlinx.coroutines.flow.Flow

data class BatchInsertResult(
    val insertedCount: Int,
    val ignoredCount: Int,
)

interface BatchRepository {
    fun observeItems(): Flow<List<BatchItem>>

    fun observeMeta(): Flow<BatchMeta?>

    suspend fun insertItems(
        items: List<BatchInputItem>,
        now: Long,
    ): BatchInsertResult

    suspend fun updateSession(
        epcNormalized: String,
        status: BatchStatus,
        updatedAt: Long,
        lastSeenAt: Long?,
        lastRssi: Int?,
        source: BatchSource?,
    ): Boolean

    suspend fun setCurrentEpc(epcNormalized: String?)

    suspend fun setLastImportAt(epochMs: Long)

    suspend fun setLastExportAt(epochMs: Long)

    suspend fun clearAll()

    suspend fun getAll(): List<BatchItem>
}
