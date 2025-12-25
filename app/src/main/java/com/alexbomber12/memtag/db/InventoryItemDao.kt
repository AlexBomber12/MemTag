package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InventoryItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InventoryItemEntity>)

    @Query("SELECT * FROM inventory_items WHERE epcNormalized = :epcNormalized LIMIT 1")
    suspend fun getByEpc(epcNormalized: String): InventoryItemEntity?

    @Query(
        "SELECT * FROM inventory_items WHERE " +
            "epcNormalized LIKE :query OR " +
            "name LIKE :query OR " +
            "content LIKE :query OR " +
            "locationPath LIKE :query",
    )
    suspend fun searchByText(query: String): List<InventoryItemEntity>
}
