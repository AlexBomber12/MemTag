package com.alexbomber12.memtag.integrations.uhf

import kotlinx.coroutines.flow.Flow

interface UhfReader {
    suspend fun initialize(): Result<Unit>

    suspend fun close(): Result<Unit>

    /**
     * Returns [UhfError.OperationInProgress] if inventory is running.
     */
    suspend fun readSingle(timeoutMs: Long = 2_000): Result<String>

    /**
     * Writes the EPC to the tag. Returns [UhfError.OperationInProgress] if inventory is running.
     */
    suspend fun writeEpc(
        epcHex: String,
        timeoutMs: Long = 5_000,
    ): Result<Unit>

    /**
     * Reads a tag and compares it to [expectedEpcHex]. Returns [UhfError.OperationInProgress] if inventory is running.
     */
    suspend fun verifyEpc(
        expectedEpcHex: String,
        timeoutMs: Long = 3_000,
    ): Result<Boolean>

    suspend fun startInventory(filterEpcHex: String? = null): Flow<TagReading>

    suspend fun stopInventory(): Result<Unit>

    suspend fun setPower(dbm: Int): Result<Unit>

    suspend fun getPower(reason: String): Result<Int>

    suspend fun getFrequencyMode(reason: String): Result<Int>

    suspend fun getProtocol(reason: String): Result<Int>

    suspend fun getRfLink(reason: String): Result<Int>

    suspend fun setRegion(region: UhfRegion): Result<Unit>

    suspend fun getRegion(reason: String): Result<UhfRegion>

    suspend fun applyDesiredConfigBestEffort(reason: String): Result<UhfApplyResult>

    suspend fun applyDesiredConfigWithReadback(reason: String): Result<UhfApplyResult>

    suspend fun runMatrixProbe(): List<MatrixProbeResult>
}
