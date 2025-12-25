package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueMetaDao {
    @Query("SELECT * FROM queue_meta WHERE id = 0")
    fun observeMeta(): Flow<QueueMetaEntity?>

    @Query("SELECT * FROM queue_meta WHERE id = 0")
    suspend fun getMeta(): QueueMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: QueueMetaEntity)
}
