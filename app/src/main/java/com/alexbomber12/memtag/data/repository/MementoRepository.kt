package com.alexbomber12.memtag.data.repository

import com.alexbomber12.memtag.domain.InventoryItem
import com.alexbomber12.memtag.domain.LookupResult
import com.alexbomber12.memtag.domain.SyncProgress
import com.alexbomber12.memtag.domain.SyncResult
import com.alexbomber12.memtag.domain.SyncState
import kotlinx.coroutines.flow.Flow

interface MementoRepository {
    suspend fun syncLibrary(
        libraryId: String,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult

    suspend fun lookupByEpc(epcRaw: String): LookupResult

    suspend fun searchInventory(
        query: String,
        limit: Int = 20,
    ): List<InventoryItem>

    fun observeSyncState(libraryId: String): Flow<SyncState?>
}
