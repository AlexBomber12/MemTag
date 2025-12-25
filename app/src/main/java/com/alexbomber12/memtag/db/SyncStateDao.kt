package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE libraryId = :libraryId LIMIT 1")
    suspend fun get(libraryId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE libraryId = :libraryId LIMIT 1")
    fun observe(libraryId: String): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
