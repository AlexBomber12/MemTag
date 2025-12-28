package com.alexbomber12.memtag.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_meta")
data class BatchMetaEntity(
    @PrimaryKey val id: Int = 0,
    val currentEpcNormalized: String? = null,
    val lastImportAt: Long? = null,
    val lastExportAt: Long? = null,
)
