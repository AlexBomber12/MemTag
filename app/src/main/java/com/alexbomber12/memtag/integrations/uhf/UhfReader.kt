package com.alexbomber12.memtag.integrations.uhf

import kotlinx.coroutines.flow.Flow

interface UhfReader {
    suspend fun initialize(): Result<Unit>

    suspend fun close(): Result<Unit>

    /**
     * Returns [UhfError.OperationInProgress] if inventory is running.
     */
    suspend fun readSingle(timeoutMs: Long = 2_000): Result<String>

    fun startInventory(filterEpcHex: String? = null): Flow<TagReading>

    suspend fun stopInventory(): Result<Unit>

    suspend fun setPower(dbm: Int): Result<Unit>

    suspend fun getPower(): Result<Int>

    suspend fun setRegion(region: UhfRegion): Result<Unit>

    suspend fun getRegion(): Result<UhfRegion>
}
