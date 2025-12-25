package com.alexbomber12.memtag.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val libraryId: String,
    val lastSyncAt: Long,
    val lastSyncStatus: String,
    val lastErrorMessage: String?,
)
