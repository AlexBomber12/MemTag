package com.alexbomber12.memtag.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inventory_items",
    indices = [
        Index(value = ["epcNormalized"], unique = true),
        Index(value = ["status"]),
        Index(value = ["category"]),
        Index(value = ["locationPath"]),
        Index(value = ["toPrint"]),
    ],
)
data class InventoryItemEntity(
    @PrimaryKey val entryId: String,
    val epcNormalized: String,
    val name: String?,
    val content: String?,
    val locationPath: String?,
    val status: String?,
    val category: String?,
    val comment: String?,
    val labelRev: String?,
    val toPrint: Boolean?,
    val um: String?,
    val qrRaw: String?,
    val photoThumbUrlOrRef: String?,
    val updatedAt: Long?,
)
