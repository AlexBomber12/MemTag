package com.alexbomber12.memtag.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queue_items",
    indices = [
        Index(value = ["epcNormalized"], unique = true),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"]),
    ],
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val epcNormalized: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val note: String?,
    val lastProximity: Int?,
)
