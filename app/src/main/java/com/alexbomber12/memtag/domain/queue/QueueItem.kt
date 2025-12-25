package com.alexbomber12.memtag.domain.queue

enum class QueueItemStatus {
    PENDING,
    FOUND,
    SKIPPED,
    NOT_FOUND,
}

data class QueueItem(
    val id: Long,
    val epcNormalized: String,
    val status: QueueItemStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val note: String?,
    val lastProximity: Int?,
)

data class QueueMeta(
    val currentEpcNormalized: String?,
    val lastImportAt: Long?,
    val lastExportAt: Long?,
)
