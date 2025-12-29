package com.alexbomber12.memtag.domain

import com.alexbomber12.memtag.integrations.memento.PagingStrategy

data class SyncResult(
    val status: SyncStatus,
    val fetchedCount: Int,
    val storedCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
    val pagingStrategy: PagingStrategy?,
    val errorMessage: String?,
) {
    val downloadedCount: Int
        get() = fetchedCount
    val savedCount: Int
        get() = storedCount
    val ignoredCount: Int
        get() = skippedCount
}

data class SyncProgress(
    val stage: SyncStage,
    val fetchedCount: Int,
    val storedCount: Int,
    val skippedCount: Int,
    val message: String? = null,
) {
    val downloadedCount: Int
        get() = fetchedCount
    val savedCount: Int
        get() = storedCount
    val ignoredCount: Int
        get() = skippedCount
}

data class SyncState(
    val libraryId: String,
    val lastSyncAt: Long,
    val lastSyncStatus: SyncStatus,
    val lastErrorMessage: String?,
)

enum class SyncStatus {
    SUCCESS,
    ERROR,
}

enum class SyncStage {
    STARTING,
    FETCHING_SCHEMA,
    FETCHING_ENTRIES,
    SAVING_BATCH,
    COMPLETED,
    ERROR,
}
