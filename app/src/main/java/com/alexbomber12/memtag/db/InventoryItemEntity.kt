package com.alexbomber12.memtag.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "inventory_items",
    primaryKeys = ["libraryId", "entryId"],
    indices = [
        Index(value = ["libraryId", "epcNormalized"], unique = true),
        Index(value = ["libraryId", "status"]),
        Index(value = ["libraryId", "category"]),
        Index(value = ["libraryId", "locationPath"]),
        Index(value = ["libraryId", "toPrint"]),
    ],
)
data class InventoryItemEntity(
    val libraryId: String,
    val entryId: String,
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
    val syncRunId: Long,
)
