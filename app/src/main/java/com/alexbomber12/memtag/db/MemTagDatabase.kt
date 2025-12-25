package com.alexbomber12.memtag.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        InventoryItemEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
)
abstract class MemTagDatabase : RoomDatabase() {
    abstract fun inventoryItemDao(): InventoryItemDao

    abstract fun syncStateDao(): SyncStateDao
}
