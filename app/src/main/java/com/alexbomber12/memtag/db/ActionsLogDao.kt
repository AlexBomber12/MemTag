package com.alexbomber12.memtag.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActionsLogDao {
    @Insert
    suspend fun insert(log: ActionsLogEntity)

    @Query("SELECT * FROM actions_log ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun recentLogs(limit: Int): List<ActionsLogEntity>
}
