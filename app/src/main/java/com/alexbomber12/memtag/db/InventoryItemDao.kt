package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InventoryItemEntity>)

    @Query(
        "SELECT * FROM inventory_items " +
            "WHERE libraryId = :libraryId AND epcNormalized = :epcNormalized " +
            "LIMIT 1",
    )
    suspend fun getByEpc(
        libraryId: String,
        epcNormalized: String,
    ): InventoryItemEntity?

    @Query(
        "SELECT * FROM inventory_items WHERE " +
            "libraryId = :libraryId AND (" +
            "epcNormalized LIKE :query OR " +
            "name LIKE :query OR " +
            "content LIKE :query OR " +
            "locationPath LIKE :query OR " +
            "um LIKE :query" +
            ") LIMIT :limit",
    )
    suspend fun searchByText(
        libraryId: String,
        query: String,
        limit: Int,
    ): List<InventoryItemEntity>

    @Query("SELECT COUNT(*) FROM inventory_items WHERE libraryId = :libraryId")
    fun observeCount(libraryId: String): Flow<Int>

    @Query(
        "DELETE FROM inventory_items " +
            "WHERE libraryId = :libraryId AND syncRunId != :currentSyncRunId",
    )
    suspend fun deleteStale(
        libraryId: String,
        currentSyncRunId: Long,
    )

    @Query(
        "DELETE FROM inventory_items " +
            "WHERE libraryId = :libraryId AND entryId = :entryId",
    )
    suspend fun deleteByEntryId(
        libraryId: String,
        entryId: String,
    ): Int
}
