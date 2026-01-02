package com.alexbomber12.memtag.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        InventoryItemEntity::class,
        ActionsLogEntity::class,
        SyncStateEntity::class,
        BatchItemEntity::class,
        BatchMetaEntity::class,
    ],
    version = 5,
)
abstract class MemTagDatabase : RoomDatabase() {
    abstract fun inventoryItemDao(): InventoryItemDao

    abstract fun actionsLogDao(): ActionsLogDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun batchDao(): BatchDao

    abstract fun batchMetaDao(): BatchMetaDao
}
