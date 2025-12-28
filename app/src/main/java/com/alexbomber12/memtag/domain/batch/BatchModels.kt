package com.alexbomber12.memtag.domain.batch

enum class BatchStatus {
    UNKNOWN,
    FOUND,
    NOT_FOUND,
    EXTRA,
}

enum class BatchSource {
    INVENTORY,
    SCAN,
    MANUAL,
}

data class BatchInputItem(
    val epcNormalized: String,
    val name: String,
)

data class BatchSessionEntry(
    val status: BatchStatus,
    val lastSeenAt: Long?,
    val lastRssi: Int?,
    val source: BatchSource?,
    val updatedAt: Long?,
)

data class BatchItem(
    val id: Long,
    val input: BatchInputItem,
    val session: BatchSessionEntry,
    val createdAt: Long,
)

data class BatchMeta(
    val currentEpcNormalized: String?,
    val lastImportAt: Long?,
    val lastExportAt: Long?,
)

data class BatchExportRow(
    val epc: String,
    val name: String,
    val status: BatchStatus,
    val updatedAt: Long?,
)

data class BatchExtraEntry(
    val epcNormalized: String,
    val lastSeenAt: Long?,
    val lastRssi: Int?,
    val source: BatchSource,
)
