package com.alexbomber12.memtag.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        InventoryItemEntity::class,
        ActionsLogEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
)
abstract class MemTagDatabase : RoomDatabase() {
    abstract fun inventoryItemDao(): InventoryItemDao

    abstract fun actionsLogDao(): ActionsLogDao

    abstract fun syncStateDao(): SyncStateDao
}
