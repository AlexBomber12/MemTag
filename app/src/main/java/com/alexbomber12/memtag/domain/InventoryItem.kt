package com.alexbomber12.memtag.domain

data class InventoryItem(
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
)
