package com.alexbomber12.memtag.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        InventoryItemEntity::class,
        ActionsLogEntity::class,
        SyncStateEntity::class,
        QueueItemEntity::class,
        QueueMetaEntity::class,
    ],
    version = 3,
)
abstract class MemTagDatabase : RoomDatabase() {
    abstract fun inventoryItemDao(): InventoryItemDao

    abstract fun actionsLogDao(): ActionsLogDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun queueDao(): QueueDao

    abstract fun queueMetaDao(): QueueMetaDao
}
