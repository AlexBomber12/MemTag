package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<BatchItemEntity>): List<Long>

    @Query("SELECT * FROM queue_items ORDER BY createdAt")
    fun getAllFlow(): Flow<List<BatchItemEntity>>

    @Query("SELECT * FROM queue_items ORDER BY createdAt")
    suspend fun getAll(): List<BatchItemEntity>

    @Query("SELECT * FROM queue_items WHERE epcNormalized = :epcNormalized LIMIT 1")
    suspend fun getByEpc(epcNormalized: String): BatchItemEntity?

    @Query(
        "UPDATE queue_items SET status = :status, updatedAt = :updatedAt, " +
            "lastSeenAt = :lastSeenAt, lastProximity = :lastRssi, source = :source " +
            "WHERE epcNormalized = :epcNormalized",
    )
    suspend fun updateSession(
        epcNormalized: String,
        status: String,
        updatedAt: Long,
        lastSeenAt: Long?,
        lastRssi: Int?,
        source: String?,
    ): Int

    @Query("DELETE FROM queue_items")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM queue_items WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
