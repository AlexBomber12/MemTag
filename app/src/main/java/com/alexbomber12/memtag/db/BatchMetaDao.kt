package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchMetaDao {
    @Query("SELECT * FROM queue_meta WHERE id = 0")
    fun observeMeta(): Flow<BatchMetaEntity?>

    @Query("SELECT * FROM queue_meta WHERE id = 0")
    suspend fun getMeta(): BatchMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: BatchMetaEntity)
}
