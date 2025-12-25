package com.alexbomber12.memtag.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "actions_log")
data class ActionsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAtEpochMs: Long,
    val actionType: String,
    val expectedEpc: String?,
    val currentEpc: String?,
    val result: String,
    val message: String?,
)
