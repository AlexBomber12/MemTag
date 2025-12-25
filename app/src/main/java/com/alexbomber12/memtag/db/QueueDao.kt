package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<QueueItemEntity>): List<Long>

    @Query(
        "SELECT * FROM queue_items ORDER BY " +
            "CASE status " +
            "WHEN 'PENDING' THEN 0 " +
            "WHEN 'FOUND' THEN 1 " +
            "WHEN 'SKIPPED' THEN 2 " +
            "WHEN 'NOT_FOUND' THEN 3 " +
            "ELSE 99 END, " +
            "createdAt",
    )
    fun getAllFlow(): Flow<List<QueueItemEntity>>

    @Query(
        "SELECT * FROM queue_items ORDER BY " +
            "CASE status " +
            "WHEN 'PENDING' THEN 0 " +
            "WHEN 'FOUND' THEN 1 " +
            "WHEN 'SKIPPED' THEN 2 " +
            "WHEN 'NOT_FOUND' THEN 3 " +
            "ELSE 99 END, " +
            "createdAt",
    )
    suspend fun getAll(): List<QueueItemEntity>

    @Query("SELECT * FROM queue_items WHERE epcNormalized = :epcNormalized LIMIT 1")
    suspend fun getByEpc(epcNormalized: String): QueueItemEntity?

    @Query(
        "UPDATE queue_items SET status = :status, updatedAt = :updatedAt " +
            "WHERE epcNormalized = :epcNormalized",
    )
    suspend fun updateStatus(
        epcNormalized: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM queue_items")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM queue_items WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
